"""把 SQLBot 相关环境变量模板追加到根目录的 .env.example 中（若不存在）。
"""

import os
from pathlib import Path

ENV_EXAMPLE_BLOCK = """
# ==============================================================================
# SQLBot (Text-to-SQL) 与 MCP 配置
# ==============================================================================
SQLBOT_MCP_URL=http://sqlbot:8001/mcp
SQLBOT_USERNAME=13800000000
SQLBOT_PASSWORD=
SQLBOT_GATEWAY_KEY=
SQLBOT_DB_USER=sqlbot_ro
SQLBOT_DB_PASSWORD=
"""


def main():
    root = Path(__file__).resolve().parent.parent
    example_file = root / ".env.example"

    if not example_file.exists():
        print(f"[!] {example_file} 不存在，创建新文件")
        example_file.write_text(ENV_EXAMPLE_BLOCK.lstrip(), encoding="utf-8")
        print("[+] 已创建并写入 SQLBot 配置块到 .env.example")
        return

    content = example_file.read_text(encoding="utf-8", errors="ignore")
    if "SQLBOT_MCP_URL" in content or "SQLBOT_USERNAME" in content:
        print("[*] .env.example 中已存在 SQLBot 相关配置，无需追加")
        return

    with open(example_file, "a", encoding="utf-8") as f:
        f.write(ENV_EXAMPLE_BLOCK)
    print("[+] 成功将 SQLBot 配置块追加到 .env.example")


if __name__ == "__main__":
    main()
