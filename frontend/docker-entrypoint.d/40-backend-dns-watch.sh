#!/bin/sh
# backend IP 漂移保活守护（随官方 nginx 镜像 entrypoint 在 nginx 启动前拉起，后台循环）
#
# 背景：Docker 内嵌 DNS（127.0.0.11）对服务名返回 TTL=600s（实测 dnsttl_probe），
# 而运行中的 nginx 不按 resolver valid 参数收敛缓存（valid=10s/0s 均实测无效），
# backend 容器重建换 IP 后，全部 /api 反代最长可 502 十分钟，直到 nginx -s reload。
#
# 方案：每 5s 用 getent 独立短连接查询（每次调用都是全新 socket，永远拿到最新
# 解析，不受 nginx 长驻 UDP socket / 缓存影响），对比 backend IP 集合，一旦变化
# 立即 nginx -s reload（优雅热加载，不断连接），强制 nginx 重新解析全部 upstream——
# 同时覆盖 /api/support/* 等仍使用静态 proxy_pass 的 location 的同款陈旧 IP 隐患。
set -u

last_ips=""
while :; do
    ips="$(getent hosts backend 2>/dev/null | awk '{print $1}' | sort -u | tr '\n' ' ')"
    if [ -n "$ips" ] && [ "$ips" != "$last_ips" ]; then
        if [ -n "$last_ips" ]; then
            echo "[backend-dns-watch] backend IPs changed: [$last_ips] -> [$ips], reloading nginx"
            nginx -s reload || echo "[backend-dns-watch] nginx -s reload failed"
        fi
        last_ips="$ips"
    fi
    sleep 5
done &
