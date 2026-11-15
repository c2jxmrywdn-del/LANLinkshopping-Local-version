#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S2 checkout characterization probe (self-cleaning, one-shot).

S2 extracted the cart load out of OrderService.doCheckout into
loadCheckoutLines -> List<Line(prodId, quantity, cartId)> and turned the
per-line cart delete into `if (cartId != null) deleteById`. Unit tests are
green; this probe proves on the running stack that ORDINARY checkout
(CheckoutDTO.cartIds path) behaves exactly as before the refactor:

  a) baseline (read-only): buyer cart rows, target product stock. The product
     is picked with read-only SQL among status=1 / review_status=1 rows with
     enough stock; products already in the cart and products attached to any
     active promotion (prod_ids) are excluded, and the probe only accepts a
     candidate whose qty*unit amount stays below every applicable campaign
     threshold -- so promoDiscount must stay 0 and the amount assertion is
     unambiguous. tier_price_json is parsed with the same rule as
     TierPricing (highest tier whose qty <= purchased qty, fallback base).
  b) cart/add qty=2 through the cart API, capture the new cartId, checkout
     exactly that one cart line (payType=corporate, random UUID idempotentKey).
  c) assertions: code=200; t_order.total_amount == unit price * 2;
     t_order_item snapshot price/quantity/subtotal and their sum agree with
     the order total; stock == baseline - 2; the probe cart row is gone;
     every pre-existing cart row survives untouched (row count == count
     after add - 1 == baseline count).
  d) POST /order/cancel/{orderNo} (code-accurate endpoint; the task text said
     /order/cancel): stock is restored to baseline, order_status=3.
  e) cleanup: SQL-delete ONLY rows keyed by this run (t_order/t_order_item by
     order_no; t_message by related_no; t_user_risk_event by ref_id;
     t_idempotent_token by this run's biz_key; t_order_outbox rows whose
     payload mentions this order_no -- an unpaid corporate checkout emits no
     paid event, so normally zero). Finally re-check stock/cart/order counts
     against baseline and that no residue remains.

If checkout succeeds but the cancel fails, the order (and its order_item /
messages / risk rows) is deliberately LEFT IN PLACE for forensics -- no raw
SQL delete of a still-live order. That case is reported as a failure.

Credentials are passed as arguments and never printed. DB access goes through
`docker exec` with the container's own environment variables, so no password
appears in this script, the command line, or the output.

Usage:
  python scripts/rfq/checkout_smoke_probe.py --phone 13900000001 \
      --password 'xxx' [--backend http://127.0.0.1:8080/api] \
      [--mysql-container shopping-mysql-1] [--keep] \
      [--out reports/rfq-checkout-smoke-evidence.json]

Exit codes: 0 all assertions passed, 1 assertion failure, 2 precondition
missing (no buyer / member discount != 1.000 / no suitable product),
3 docker or DB unreachable. Console output is ASCII on purpose (Windows GBK
consoles mangle CJK); the UTF-8 evidence trail goes to --out.
"""

from __future__ import annotations

import argparse
import json
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
from decimal import Decimal, InvalidOperation
from http.cookiejar import CookieJar


REMARK = "checkout-smoke-probe"
QTY = 2
PROMO_TYPES = ("full_reduce", "discount", "flash_sale")


class Client:
    """Cookie-jar session with CSRF header, same shape as points_race_probe."""

    def __init__(self, backend: str):
        self.backend = backend.rstrip("/")
        self.jar = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = ""

    def call(self, path, method="GET", body=None, headers=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(self.backend + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            with self.opener.open(req, timeout=60) as resp:
                return resp.status, _json(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, _json(e.read())
        except Exception as e:  # noqa: BLE001 - probe must report, not crash
            return -1, {"error": str(e)}

    def login(self, phone: str, password: str) -> None:
        status, body = self.call("/auth/login", "POST",
                                 {"phone": phone, "password": password})
        if status != 200 or (body or {}).get("code") != 200:
            raise SystemExit("login failed HTTP=%s code=%s" % (status, (body or {}).get("code")))
        if (body.get("data") or {}).get("require2fa"):
            raise SystemExit("account has 2FA enabled; use the demo buyer without 2FA")
        _, csrf_body = self.call("/user/csrf-token")
        data = (csrf_body or {}).get("data") or {}
        self.csrf = str(data.get("token") or data.get("csrfToken") or "")
        if not self.csrf:
            raise SystemExit("no CSRF token returned")

    def write(self, path, body=None):
        return self.call(path, "POST", body, {"X-CSRF-TOKEN": self.csrf})

    def delete(self, path):
        return self.call(path, "DELETE", None, {"X-CSRF-TOKEN": self.csrf})


def _json(payload):
    try:
        return json.loads(payload.decode("utf-8"))
    except Exception:  # noqa: BLE001
        return None


class Db:
    """Read/write helper that keeps credentials inside the container."""

    def __init__(self, container: str):
        self.container = container

    def run(self, stmt: str) -> str:
        inner = ('mysql --default-character-set=utf8mb4 -uroot '
                 '-p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE" -N -B -e '
                 + shlex.quote(stmt))
        proc = subprocess.run(
            ["docker", "exec", self.container, "sh", "-c", inner],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=90)
        if proc.returncode != 0:
            raise SystemExit("docker/mysql failed: %s" % (proc.stderr or "")[:300])
        return (proc.stdout or "").strip()

    def rows(self, stmt: str):
        out = self.run(stmt)
        return [line.split("\t") for line in out.splitlines() if line]


class Probe:
    def __init__(self, out_path: str, meta: dict):
        self.out_path = out_path
        self.evidence = {
            "probe": "rfq-checkout-smoke",
            "purpose": "S2 ordinary checkout (cartIds path) end-to-end invariance",
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "meta": meta,
            "checks": [],
            "notes": [],
            "cleanup": {},
            "summary": {},
        }

    def check(self, name: str, ok: bool, detail=None):
        self.evidence["checks"].append({"name": name, "ok": bool(ok), "detail": detail})
        print("[%s] %s" % ("PASS" if ok else "FAIL", name))
        if not ok:
            print("       detail: %s" % json.dumps(detail, ensure_ascii=True)[:600])
        self.flush()

    def note(self, name: str, detail):
        self.evidence["notes"].append({"name": name, "detail": detail})
        self.flush()

    def flush(self):
        if self.out_path:
            with open(self.out_path, "w", encoding="utf-8") as fh:
                json.dump(self.evidence, fh, ensure_ascii=False, indent=2)

    @property
    def failed(self):
        return [c for c in self.evidence["checks"] if c.get("ok") is False]

    def finish(self, code: int):
        total = len(self.evidence["checks"])
        failed = len(self.failed)
        self.evidence["summary"] = {
            "checks": total,
            "passed": total - failed,
            "failed": failed,
            "exit_code": code,
        }
        self.flush()
        print("\n%d checks, %d failed, exit=%d, evidence: %s"
              % (total, failed, code, self.out_path))
        return code


def dec(value) -> Decimal:
    if value is None:
        return Decimal("0")
    text = str(value).strip()
    if text in ("", "NULL", "None"):
        return Decimal("0")
    try:
        return Decimal(text)
    except InvalidOperation:
        return Decimal("0")


def sql_in(values):
    """Quote internally-generated identifiers for an SQL IN clause."""
    return ",".join("'" + str(v).replace("'", "''") + "'" for v in values)


def tier_unit(base: Decimal, tier_json: str, qty: int) -> Decimal:
    """Mirror TierPricing.resolve: highest tier with minQty <= qty, base fallback."""
    if not tier_json or tier_json in ("NULL", "null", ""):
        return base
    try:
        arr = json.loads(tier_json)
    except Exception:  # noqa: BLE001 - dirty JSON falls back to base price
        return base
    if not isinstance(arr, list):
        return base
    hit = None
    for node in arr:
        if not isinstance(node, dict):
            continue
        min_qty = None
        for key in ("qty", "minQty", "min_qty"):
            if node.get(key) is not None:
                try:
                    min_qty = int(node[key])
                except (TypeError, ValueError):
                    min_qty = None
                break
        price = None
        for key in ("price", "unitPrice", "unit_price"):
            if node.get(key) is not None:
                try:
                    price = dec(node[key])
                except Exception:  # noqa: BLE001
                    price = None
                break
        if min_qty is None or min_qty <= 0 or price is None or price <= 0:
            continue
        if qty >= min_qty and (hit is None or min_qty >= hit[0]):
            hit = (min_qty, price)
    if hit is None:
        return base
    if base > 0 and hit[1] > base:
        return base  # tier above base price is treated as misconfigured
    return hit[1]


def campaign_rows(db: Db):
    rows = db.rows(
        "SELECT campaign_id, type, scope, ind_id, mer_id, threshold, prod_ids "
        "FROM t_campaign WHERE status = 1 AND review_status = 1 "
        "AND type IN ('%s') "
        "AND (start_time IS NULL OR start_time <= NOW()) "
        "AND (end_time IS NULL OR end_time >= NOW())" % "','".join(PROMO_TYPES))
    out = []
    for r in rows:
        r = (r + [""] * 7)[:7]
        out.append({
            "campaign_id": r[0], "type": r[1], "scope": r[2], "ind_id": r[3],
            "mer_id": r[4], "threshold": r[5], "prod_ids": r[6],
        })
    return out


def parse_prod_ids(raw: str):
    out = set()
    for part in (raw or "").split(","):
        part = part.strip()
        if part.isdigit():
            out.add(part)
    return out


def pick_product(db: Db, uid: str, cart_prod_ids, campaigns):
    """Cheapest product that is buyable, untouched by active promos, not in cart."""
    promo_prod_ids = set()
    for c in campaigns:
        promo_prod_ids |= parse_prod_ids(c["prod_ids"])
    rows = db.rows(
        "SELECT prod_id, mer_id, ind_id, price, stock, tier_price_json, title "
        "FROM t_product WHERE status = 1 AND review_status = 1 AND price > 0 "
        "AND stock >= %d AND (deleted IS NULL OR deleted = 0) "
        "ORDER BY price ASC LIMIT 200" % (QTY + 5))
    candidates = []
    for r in rows:
        if len(r) < 6:
            continue
        prod_id, mer_id, ind_id, price = r[0], r[1], r[2], dec(r[3])
        stock = int(r[4] or 0)
        tier_json = r[5]
        title = "\t".join(r[6:]) if len(r) > 6 else ""
        candidates.append({
            "prod_id": prod_id, "mer_id": mer_id, "ind_id": ind_id,
            "base_price": price, "stock": stock, "tier_price_json": tier_json,
            "title": title,
        })
    for cand in candidates:
        if cand["prod_id"] in cart_prod_ids or cand["prod_id"] in promo_prod_ids:
            continue
        unit = tier_unit(cand["base_price"], cand["tier_price_json"], QTY)
        total = unit * QTY
        safe = True
        for c in campaigns:
            if c["mer_id"]:  # merchant promo: only via prod_ids, excluded above
                continue
            if c["scope"] == "ind" and str(c["ind_id"]) != str(cand["ind_id"]):
                continue
            if total >= dec(c["threshold"]):
                safe = False  # a platform promo would apply to this amount
                break
        if not safe:
            continue
        cand["unit_price"] = unit
        cand["expected_total"] = total
        cand["tier_hit"] = unit != cand["base_price"]
        return cand
    return None


class StopProbe(Exception):
    """Abort the main flow but still run the cleanup block and write evidence."""

    def __init__(self, code: int):
        super().__init__("probe stopped")
        self.code = code


def safe_run(p: Probe, db: Db, stmt: str, label: str) -> bool:
    """Run one cleanup statement; a DB failure becomes a failed check, not a crash."""
    try:
        db.run(stmt)
        return True
    except BaseException as e:  # noqa: BLE001 - cleanup must fully report
        p.check("cleanup step failed: %s" % label, False, {"error": str(e)[:300]})
        return False


def cart_rows(db: Db, uid: str):
    rows = db.rows("SELECT cart_id, prod_id, quantity, checked FROM t_cart "
                   "WHERE user_id = %s ORDER BY cart_id" % uid)
    return [{"cart_id": r[0], "prod_id": r[1], "quantity": r[2],
             "checked": r[3] if len(r) > 3 else ""} for r in rows]


def money(value: Decimal) -> str:
    return format(value.normalize(), "f") if value != 0 else "0"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://127.0.0.1:8080/api")
    ap.add_argument("--phone", required=True)
    ap.add_argument("--password", required=True)
    ap.add_argument("--mysql-container", default="shopping-mysql-1")
    ap.add_argument("--keep", action="store_true", help="skip cleanup (leaves probe data behind)")
    ap.add_argument("--out", default="reports/rfq-checkout-smoke-evidence.json")
    args = ap.parse_args()

    p = Probe(args.out, {
        "backend": args.backend,
        "mysql_container": args.mysql_container,
        "phone_suffix": args.phone[-4:],
        "qty": QTY,
        "remark": REMARK,
    })
    db = Db(args.mysql_container)

    try:
        db.run("SELECT 1")
    except SystemExit as e:
        print(str(e))
        return p.finish(3)

    uid_rows = db.rows("SELECT user_id, role_id FROM t_user WHERE phone = '%s' LIMIT 1"
                       % args.phone.replace("'", "''"))
    if not uid_rows:
        print("buyer phone not found in t_user")
        p.note("precondition", {"reason": "buyer phone missing"})
        return p.finish(2)
    uid, role_id = uid_rows[0][0], uid_rows[0][1]

    # Member level discount must be 1.000 for a plain "unit price * qty" total.
    rate_rows = db.rows(
        "SELECT l.discount_rate FROM t_member_card c "
        "LEFT JOIN t_member_level l ON l.level_id = c.level_id "
        "WHERE c.user_id = %s" % uid)
    member_rate = dec(rate_rows[0][0]) if rate_rows else Decimal("1")
    if member_rate != Decimal("1"):
        p.note("precondition", {"reason": "member discount rate is not 1.000",
                                "member_rate": money(member_rate)})
        print("precondition missing: member discount rate=%s (!= 1.000)"
              % money(member_rate))
        return p.finish(2)

    base_cart = cart_rows(db, uid)
    base_cart_ids = [r["cart_id"] for r in base_cart]
    base_orders = int(db.rows("SELECT COUNT(*) FROM t_order WHERE user_id = %s" % uid)[0][0])
    base_messages = int(db.rows("SELECT COUNT(*) FROM t_message WHERE user_id = %s" % uid)[0][0])
    base_risk = int(db.rows("SELECT COUNT(*) FROM t_user_risk_event WHERE user_id = %s" % uid)[0][0])
    base_outbox = int(db.rows("SELECT COUNT(*) FROM t_order_outbox")[0][0])

    campaigns = campaign_rows(db)
    cand = pick_product(db, uid, {r["prod_id"] for r in base_cart}, campaigns)
    if cand is None:
        p.note("precondition", {"reason": "no suitable product "
                                "(status=1, stock>=%d, no active promo)" % (QTY + 5),
                                "active_campaigns": campaigns})
        print("precondition missing: no suitable product (see evidence)")
        return p.finish(2)

    prod_id = cand["prod_id"]
    base_stock = cand["stock"]
    unit = cand["unit_price"]
    expected_total = cand["expected_total"]

    p.note("baseline", {
        "user_id": uid, "role_id": role_id, "member_discount_rate": money(member_rate),
        "cart_rows": base_cart, "cart_count": len(base_cart),
        "orders": base_orders, "messages": base_messages, "risk_events": base_risk,
        "outbox_rows": base_outbox,
        "active_campaigns": campaigns,
    })
    p.note("target product", {
        "prod_id": prod_id, "title": cand["title"], "mer_id": cand["mer_id"],
        "ind_id": cand["ind_id"], "base_price": money(cand["base_price"]),
        "tier_price_json": cand["tier_price_json"],
        "tier_hit_at_qty": cand["tier_hit"], "unit_price": money(unit),
        "qty": QTY, "expected_total": money(expected_total),
        "stock_baseline": base_stock,
    })
    p.check("precondition: expected total == unit price * qty",
            expected_total == unit * QTY and unit > 0,
            {"unit": money(unit), "qty": QTY, "expected": money(expected_total)})

    client = Client(args.backend)
    try:
        client.login(args.phone, args.password)
    except SystemExit as e:
        print(str(e))
        p.check("login", False, {"error": str(e)})
        return p.finish(1)

    idem_key = str(uuid.uuid4())
    probe_cart_id = None
    order_no = None
    o2 = None
    cancel_ok = False
    order_deleted = False
    cart_after_add = []
    run_start_db = None
    stop_code = None

    try:
        # b) add one fresh cart line and capture its cartId
        st, bd = client.write("/cart/add", {"prodId": int(prod_id), "quantity": QTY})
        p.check("cart/add accepted (qty=%d)" % QTY,
                st == 200 and (bd or {}).get("code") == 200,
                {"http": st, "code": (bd or {}).get("code"),
                 "message": (bd or {}).get("message")})
        if not (st == 200 and (bd or {}).get("code") == 200):
            raise StopProbe(1)

        cart_after_add = cart_rows(db, uid)
        new_rows = [r for r in cart_after_add if r["prod_id"] == prod_id]
        probe_cart_id = new_rows[0]["cart_id"] if len(new_rows) == 1 else None
        p.check("cart/add created exactly one new line for the target product",
                len(new_rows) == 1 and probe_cart_id not in base_cart_ids,
                {"rows": cart_after_add, "probe_cart_id": probe_cart_id,
                 "baseline_ids": base_cart_ids})
        p.check("cart row count after add == baseline + 1",
                len(cart_after_add) == len(base_cart) + 1,
                {"after_add": len(cart_after_add), "baseline": len(base_cart)})
        p.check("new cart line quantity == %d" % QTY,
                bool(new_rows) and new_rows[0]["quantity"] == str(QTY),
                {"row": new_rows[0] if new_rows else None})
        if probe_cart_id is None:
            raise StopProbe(1)

        # Preflight through the cart API: unit price snapshot matches DB math.
        st, bd = client.call("/cart/list")
        items = (bd or {}).get("data") or []
        hit = [i for i in items if str(i.get("cartId")) == str(probe_cart_id)]
        p.check("cart/list shows the probe line with server-side unit price",
                st == 200 and len(hit) == 1
                and dec(hit[0].get("price")) == unit
                and hit[0].get("quantity") == QTY
                and dec(hit[0].get("subtotal")) == expected_total,
                {"http": st, "item": hit[0] if hit else None,
                 "expected_unit": money(unit), "expected_subtotal": money(expected_total)})

        run_start_db = db.run("SELECT DATE_SUB(NOW(), INTERVAL 5 SECOND)")

        # c) ordinary checkout: exactly this one cart line, corporate, UUID idempotency key
        body = {
            "cartIds": [int(probe_cart_id)],
            "receiver": "Checkout Smoke Probe",
            "phone": args.phone,
            "address": "checkout-smoke-probe (auto cleanup)",
            "remark": REMARK,
            "payType": "corporate",
            "idempotentKey": idem_key,
        }
        st, bd = client.write("/order/checkout", body)
        resp = bd or {}
        data = resp.get("data") or {}
        order_no = data.get("orderNo")
        p.check("checkout accepted (HTTP 200 / code 200)",
                st == 200 and resp.get("code") == 200,
                {"http": st, "code": resp.get("code"), "message": resp.get("message"),
                 "orderNo": order_no, "totalAmount": data.get("totalAmount")})

        # Discover the order robustly: response -> idempotent token -> remark window
        tok = db.rows(
            "SELECT result_ref FROM t_idempotent_token WHERE user_id = %s "
            "AND biz_type = 'checkout' AND biz_key = '%s' AND result_ref IS NOT NULL"
            % (uid, idem_key.replace("'", "''")))
        found = {order_no} if order_no else set()
        found |= {r[0] for r in tok if r and r[0]}
        found |= {r[0] for r in db.rows(
            "SELECT order_no FROM t_order WHERE user_id = %s AND remark = '%s' "
            "AND create_time >= '%s'" % (uid, REMARK, run_start_db))}
        found.discard(None)
        order_no = sorted(found)[0] if found else None
        p.check("exactly one order was created by this run",
                len(found) == 1, {"order_nos": sorted(found)})
        if order_no is None:
            raise StopProbe(1)

        o_rows = db.rows(
            "SELECT total_amount, pay_type, pay_status, order_status, promo_discount, "
            "coupon_discount, point_deduct_amount, promo_title FROM t_order "
            "WHERE order_no = '%s' AND user_id = %s" % (order_no, uid))
        order = o_rows[0] if o_rows else None
        total = dec(order[0]) if order else Decimal("0")
        p.note("order row", {
            "order_no": order_no,
            "total_amount": money(total) if order else None,
            "pay_type": order[1] if order else None,
            "pay_status": order[2] if order else None,
            "order_status": order[3] if order else None,
            "promo_discount": order[4] if order else None,
            "coupon_discount": order[5] if order else None,
            "point_deduct_amount": order[6] if order else None,
            "promo_title": order[7] if order else None,
        })
        p.check("order total_amount == unit price x qty",
                order is not None and total == expected_total,
                {"expected": money(expected_total), "actual": money(total)})
        p.check("no promo/coupon/points discount disturbed the amount",
                order is not None
                and dec(order[4]) == 0 and dec(order[5]) == 0 and dec(order[6]) == 0,
                {"promo_discount": order[4] if order else None,
                 "coupon_discount": order[5] if order else None,
                 "point_deduct_amount": order[6] if order else None})
        p.check("order is unpaid corporate as requested",
                order is not None and order[1] == "corporate"
                and str(order[2]) == "0" and str(order[3]) == "0",
                {"pay_type": order[1] if order else None,
                 "pay_status": order[2] if order else None,
                 "order_status": order[3] if order else None})

        it_rows = db.rows(
            "SELECT prod_id, price, quantity, subtotal FROM t_order_item "
            "WHERE order_no = '%s'" % order_no)
        p.note("order items", it_rows)
        item_ok = len(it_rows) == 1
        if item_ok:
            ip, iprice, iqty, isub = it_rows[0]
            item_ok = (str(ip) == str(prod_id) and dec(iprice) == unit
                       and int(iqty) == QTY and dec(isub) == expected_total)
        p.check("order_item snapshot price/qty/subtotal match the order total source",
                item_ok,
                {"items": it_rows, "expected_unit": money(unit), "qty": QTY,
                 "expected_subtotal": money(expected_total)})
        items_sum = sum(dec(r[3]) for r in it_rows)
        p.check("sum(order_item.subtotal) == order.total_amount",
                order is not None and items_sum == total,
                {"items_sum": money(items_sum), "total": money(total)})

        stock_now = int(db.rows("SELECT stock FROM t_product WHERE prod_id = %s" % prod_id)[0][0])
        p.note("stock after checkout", {"baseline": base_stock, "now": stock_now})
        p.check("stock == baseline - %d" % QTY, stock_now == base_stock - QTY,
                {"baseline": base_stock, "expected": base_stock - QTY, "actual": stock_now})

        cart_after_checkout = cart_rows(db, uid)
        p.check("probe cart row was deleted by checkout",
                all(r["cart_id"] != probe_cart_id for r in cart_after_checkout),
                {"probe_cart_id": probe_cart_id, "rows": cart_after_checkout})
        p.check("pre-existing cart rows untouched (count == after_add - 1 == baseline)",
                cart_after_checkout == base_cart
                and len(cart_after_checkout) == len(cart_after_add) - 1,
                {"baseline": base_cart, "after_add": cart_after_add,
                 "after_checkout": cart_after_checkout})
        orders_now = int(db.rows("SELECT COUNT(*) FROM t_order WHERE user_id = %s" % uid)[0][0])
        p.check("order count == baseline + 1", orders_now == base_orders + 1,
                {"baseline": base_orders, "now": orders_now})

        # d) cancel restores stock (business-level cancel, no raw SQL)
        st, bd = client.write("/order/cancel/%s" % order_no)
        cancel_ok = st == 200 and (bd or {}).get("code") == 200
        p.check("cancel accepted (POST /order/cancel/{orderNo})",
                cancel_ok, {"http": st, "code": (bd or {}).get("code"),
                            "message": (bd or {}).get("message")})
        o2 = db.rows("SELECT order_status, pay_status FROM t_order "
                     "WHERE order_no = '%s'" % order_no)
        p.check("order_status == 3 after cancel",
                bool(o2) and str(o2[0][0]) == "3",
                {"order_status": o2[0][0] if o2 else None,
                 "pay_status": o2[0][1] if o2 else None})
        stock_after_cancel = int(db.rows(
            "SELECT stock FROM t_product WHERE prod_id = %s" % prod_id)[0][0])
        p.check("stock restored to baseline after cancel",
                stock_after_cancel == base_stock,
                {"baseline": base_stock, "actual": stock_after_cancel})
        p.check("cart rows still equal baseline after cancel",
                cart_rows(db, uid) == base_cart,
                {"baseline": base_cart, "actual": cart_rows(db, uid)})

        if not cancel_ok:
            p.note("cancel failed", {
                "action": "order kept intact for forensics; no SQL delete performed",
                "order_no": order_no,
            })
    except StopProbe as e:
        stop_code = e.code
        p.note("probe stopped early", {"exit_code": e.code})
    except Exception as e:  # noqa: BLE001 - report instead of losing the evidence
        p.check("probe ran to completion without unexpected error", False,
                {"error": repr(e)[:400]})
    finally:
        if not args.keep:
            cleanup = p.evidence["cleanup"]
            cleanup["mode"] = "cleanup"
            # Remove a leftover probe cart line only if checkout did not consume it.
            if probe_cart_id is not None:
                left = db.rows("SELECT cart_id FROM t_cart WHERE cart_id = %s AND user_id = %s"
                               % (probe_cart_id, uid))
                if left:
                    st, bd = client.delete("/cart/%s" % probe_cart_id)
                    if st != 200 or (bd or {}).get("code") != 200:
                        safe_run(p, db, "DELETE FROM t_cart WHERE cart_id = %s "
                                 "AND user_id = %s" % (probe_cart_id, uid),
                                 "probe_cart_line")
                    cleanup["probe_cart_line_removed"] = True

            if order_no and cancel_ok and not args.keep:
                # Delete ONLY rows produced by this run, keyed by its order number.
                ins = order_no.replace("'", "''")
                safe_run(p, db, "DELETE FROM t_message WHERE user_id = %s "
                         "AND related_no = '%s'" % (uid, ins), "messages")
                safe_run(p, db, "DELETE FROM t_order_item WHERE order_no = '%s'" % ins,
                         "order_item")
                safe_run(p, db, "DELETE FROM t_order WHERE user_id = %s AND order_no = '%s'"
                         % (uid, ins), "order")
                safe_run(p, db, "DELETE FROM t_user_risk_event WHERE user_id = %s "
                         "AND ref_type = 'order' AND ref_id = '%s'" % (uid, ins),
                         "risk_event")
                safe_run(p, db, "DELETE FROM t_order_outbox WHERE payload LIKE '%%%s%%'" % ins,
                         "order_outbox")
                safe_run(p, db, "DELETE FROM t_idempotent_token WHERE user_id = %s "
                         "AND biz_type = 'checkout' AND biz_key = '%s'"
                         % (uid, idem_key.replace("'", "''")), "idempotent_token")
                order_deleted = True
                cleanup["order_rows_deleted"] = True
                cleanup["deleted_scope"] = [
                    "t_message(user_id, related_no=order_no)",
                    "t_order_item(order_no)",
                    "t_order(user_id, order_no)",
                    "t_user_risk_event(user_id, ref_type=order, ref_id=order_no)",
                    "t_order_outbox(payload contains order_no)",
                    "t_idempotent_token(user_id, biz_type=checkout, biz_key)",
                ]
            elif order_no and not cancel_ok:
                # Preserve the live order and everything linked to it.
                cleanup["order_rows_deleted"] = False
                cleanup["preserved_order_no"] = order_no
                cleanup["reason"] = "cancel failed; raw SQL delete of a live order is forbidden"
            elif not order_no:
                safe_run(p, db, "DELETE FROM t_idempotent_token WHERE user_id = %s "
                         "AND biz_type = 'checkout' AND biz_key = '%s'"
                         % (uid, idem_key.replace("'", "''")), "idempotent_token")
                cleanup["order_rows_deleted"] = False
                cleanup["reason"] = "no order created"

            # Final state checks (wrapped: a failed read must still leave evidence)
            try:
                stock_final = int(db.rows(
                    "SELECT stock FROM t_product WHERE prod_id = %s" % prod_id)[0][0])
                cart_final = cart_rows(db, uid)
                orders_final = int(db.rows(
                    "SELECT COUNT(*) FROM t_order WHERE user_id = %s" % uid)[0][0])
                messages_final = int(db.rows(
                    "SELECT COUNT(*) FROM t_message WHERE user_id = %s" % uid)[0][0])
                risk_final = int(db.rows(
                    "SELECT COUNT(*) FROM t_user_risk_event WHERE user_id = %s" % uid)[0][0])
                outbox_final = int(db.rows("SELECT COUNT(*) FROM t_order_outbox")[0][0])
                cleanup["final"] = {
                    "stock": stock_final, "stock_baseline": base_stock,
                    "cart": cart_final, "cart_baseline": base_cart,
                    "orders": orders_final, "orders_baseline": base_orders,
                    "messages": messages_final, "messages_baseline": base_messages,
                    "risk_events": risk_final, "risk_baseline": base_risk,
                    "outbox": outbox_final, "outbox_baseline": base_outbox,
                }
                if cancel_ok or order_no is None:
                    p.check("cleanup: stock restored to baseline",
                            stock_final == base_stock,
                            {"baseline": base_stock, "final": stock_final})
                    p.check("cleanup: cart rows restored to baseline",
                            cart_final == base_cart,
                            {"baseline": base_cart, "final": cart_final})
                    p.check("cleanup: order/message/risk/outbox counts restored",
                            orders_final == base_orders and messages_final == base_messages
                            and risk_final == base_risk and outbox_final == base_outbox,
                            {"orders": [base_orders, orders_final],
                             "messages": [base_messages, messages_final],
                             "risk": [base_risk, risk_final],
                             "outbox": [base_outbox, outbox_final]})
                    if order_deleted:
                        ins = order_no.replace("'", "''")
                        residue = db.rows(
                            "SELECT (SELECT COUNT(*) FROM t_order WHERE order_no = '%s') + "
                            "(SELECT COUNT(*) FROM t_order_item WHERE order_no = '%s') + "
                            "(SELECT COUNT(*) FROM t_message WHERE user_id = %s AND related_no = '%s') + "
                            "(SELECT COUNT(*) FROM t_user_risk_event WHERE user_id = %s "
                            " AND ref_type = 'order' AND ref_id = '%s') + "
                            "(SELECT COUNT(*) FROM t_order_outbox WHERE payload LIKE '%%%s%%') + "
                            "(SELECT COUNT(*) FROM t_idempotent_token WHERE user_id = %s "
                            " AND biz_type = 'checkout' AND biz_key = '%s')"
                            % (ins, ins, uid, ins, uid, ins, ins, uid,
                               idem_key.replace("'", "''")))
                        p.check("cleanup: no probe residue for this order",
                                bool(residue) and residue[0][0] == "0",
                                {"residue_count": residue[0][0] if residue else None})
                        p.check("cleanup: outbox had no paid-event row for the unpaid order",
                                outbox_final == base_outbox,
                                {"baseline": base_outbox, "final": outbox_final})
                else:
                    p.check("forensics: live order preserved (cancel failed, no SQL delete)",
                            bool(o2) and str(o2[0][0]) != "3",
                            {"order_no": order_no,
                             "order_status": o2[0][0] if o2 else None,
                             "stock_final": stock_final, "stock_baseline": base_stock,
                             "cart_final": cart_final})
            except BaseException as e:  # noqa: BLE001 - final verification must not crash
                p.check("cleanup: final state verification failed", False,
                        {"error": str(e)[:300]})
        else:
            p.evidence["cleanup"] = {"mode": "kept", "reason": "--keep"}

    code = stop_code if stop_code is not None else (1 if p.failed else 0)
    if p.failed:
        code = 1
    return p.finish(code)


if __name__ == "__main__":
    sys.exit(main())
