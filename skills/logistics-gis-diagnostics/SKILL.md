---
name: logistics-gis-diagnostics
description: Automated diagnostics and troubleshooting workflow for logistics GIS dynamic maps, dual-end (PC and Mobile Web) touch and pointer interactions, SVG coordinate transforms, highway corridor pathing, high-contrast visual accessibility, and merchant multi-tenant logistics isolation.
metadata:
  author: LANLink Logistics Engineering
  version: '1.0'
---

# Logistics GIS & Dual-End Map Diagnostics Workflow

This skill provides an end-to-end diagnostic, verification, and debugging runbook for web-based dynamic logistics GIS maps, interactive SVG canvas gestures across desktop and mobile browsers, route corridor interpolation, and merchant order fulfillment isolation.

---

## 1. Trigger Conditions & Symptoms

Activate this workflow whenever experiencing any of the following issues:
1. **Mobile Gesture Deadlock**: Scrolling down a mobile page freezes or locks up when touching the map area.
2. **Inverted Canvas Dragging**: Mouse or finger drag moves opposite to the intended direction (panning right shifts content left).
3. **Pinch-to-Zoom Flashing / Jitter**: Touch points cause sudden zoom jumps or reset view coordinates to origin.
4. **Low Contrast / Black Canvas**: Dark mode or high-tech background hides provincial borders, major rivers, or coastlines.
5. **Address Geocoding Failures**: Orders with English or Pinyin addresses (e.g. `Chongqing Test Road 1`) fail to resolve to Chinese city coordinate hubs.
6. **Data Leakage in Split Shipping**: Multi-merchant orders leaking cross-store waybills to unauthorized vendors.

---

## 2. Core Diagnostic Checklist

### A. Mobile Touch Action & Event Handling
- [ ] **Ensure CSS `touch-action: pan-y;` on map viewport**:
  - `touch-action: none` locks the entire viewport scroll on touch devices when touching the map.
  - Using `touch-action: pan-y;` allows vertical finger scrolling on mobile web pages while preserving map interactions.
- [ ] **Single Pointer Lifecycle (`PointerEvent`)**:
  - Deprecate mixed `@touchstart`, `@touchmove`, `@touchend` handlers alongside `@mousedown`/`@mousemove`.
  - Prefer unified `@pointerdown`, `@pointermove`, `@pointerup`, `@pointercancel`.
  - Always call `el.setPointerCapture(e.pointerId)` on down and `el.releasePointerCapture(e.pointerId)` on up/cancel.

### B. SVG ViewBox Coordinate Mathematics
- [ ] **Sign & Polarity of Pan Offsets**:
  - Standard SVG `viewBox="minX minY width height"`.
  - When dragging right by `dx`, the visible region shifts left, meaning `minX` must decrease:
    $$\text{viewBoxX} = \frac{W - w}{2} - \text{panX}$$
  - A positive sign ($+ \text{panX}$) results in inverted drag motion.
- [ ] **Zoom Scaling Invariance**:
  - Clamping zoom factor: typically $0.6 \le \text{scale} \le 3.5$.
  - View dimensions: $w = W / \text{scale}$, $h = H / \text{scale}$.

### C. Multi-Language Geocoding & Corridor Interpolation
- [ ] **Multi-Dialect City Token Matching**:
  - Match city aliases across:
    - Simplified Chinese: `重庆`, `深圳`, `广州`
    - Pinyin / English: `chongqing`, `shenzhen`, `guangzhou`
  - Fallback logic: check recipient province/city fields before falling back to address text parsing.
- [ ] **Highway Freight Corridors (G-Expressways)**:
  - Interpolate transit steps along realistic logistics corridors rather than naive straight lines:
    - *G65 Corridor*: Shenzhen -> Guangzhou -> Shaoguan -> Chenzhou -> Huaihua -> Qianjiang -> Chongqing.
    - *G4 Corridor*: Shenzhen -> Wuhan -> Zhengzhou -> Beijing.
    - *G15 Corridor*: Shenzhen -> Xiamen -> Ningbo -> Shanghai.
- [ ] **Dynamic Active Province Highlighting**:
  - Extract all passed provinces along the corridor path.
  - Dynamically highlight passed provinces on the base map with `#0369a1` / `#0284c7` border glows.

### D. Visual Accessibility & Contrast
- [ ] **Theme Contrast Switcher**:
  - Provide both standard dark tech palette (`#080e18`) and high-contrast night palette (`#030712`).
  - Province fill: `#132238` with border `#1e3a5f` or high-contrast `#38bdf8`.
  - River systems (Yangtze, Yellow River): visible cyan line `#06b6d4` with subtle dash array.
  - High-visibility markers: pulsing concentric circles with SVG `<animate>` tags.

### E. B2B Merchant Center Fulfillment Isolation
- [ ] **Status 4 (Partially Shipped) Query Scoping**:
  - In merchant fulfillment workflows, status 4 orders still require fulfillment actions.
  - Mapper queries for pending shipment MUST query `status IN (0, 4)`.
- [ ] **Waybill Item Level Scoping**:
  - When loading waybills for a multi-store order, query `LogisticsWaybillItem` filtered by the merchant's own product IDs or order item IDs.
  - Prevent cross-merchant logistics waybill leakage.

---

## 3. Automated Verification Runbook

Execute the following commands to guarantee end-to-end regression-free operation:

```bash
# 1. Frontend Unit Tests (Vitest)
npm --prefix frontend test -- --run

# 2. Production Bundle Verification (Vite)
npm --prefix frontend run build

# 3. Backend Unit & Multi-Tenant Isolation Tests (JDK 17)
mvn -f backend/pom.xml test "-Dtest=LogisticsSplitShipTest"

# 4. Zero-Emoji Compliance Check
# Ensure no emoji characters exist in production UI components
```

---

## 4. Anti-Patterns & Pitfalls to Avoid

| Pitfall | Impact | Correct Approach |
| :--- | :--- | :--- |
| `touch-action: none` on map container | User unable to scroll mobile webpage past the map | Use `touch-action: pan-y;` or dedicated gesture toggle |
| Raw touch coordinates without identifier tracking | Multi-touch pinch causes sudden jumps | Track touch points in a `Map<pointerId, Coordinates>` |
| Reversing panX in `viewBox` calculation | Mouse moves right, map pans left | Subtract `panX` from center origin |
| Straight-line geographic interpolation | Trucks appear to drive across oceans / mountains | Route via National Expressways (G-Corridors) |
| Hardcoded Chinese-only regex for address | Foreign/pinyin test addresses crash route builder | Normalize lowercase string and test Pinyin token dictionary |
| Leaking items in `merchantOrderDetail` | Compliance and privacy violation in B2B platform | Inner-join items by `mer_id` |
