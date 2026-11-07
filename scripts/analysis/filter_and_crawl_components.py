import json
import urllib.request
import re
import os
import time

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9'
}

def fetch(url):
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=12) as r:
            return r.status, r.read().decode('utf-8', errors='ignore')
    except Exception as e:
        return None, str(e)

def main():
    with open('reports/21st_components_discovered.json', 'r', encoding='utf-8') as f:
        comps = json.load(f)

    # Keywords we are targeting for dynamic UI/UX in B2B & Finance & Logistics & Mobile
    keywords = [
        'spotlight-card', 'glow-card', 'border-beam', 'shine-border',
        'tabs', 'animated-tabs', 'sliding-tabs',
        'number-ticker', 'counter', 'animated-counter',
        'drawer', 'bottom-sheet', 'sheet',
        'pulse', 'badge', 'radar', 'shimmer',
        'calculator', 'slider', 'pricing', 'bento'
    ]

    selected = {}
    for slug, info in comps.items():
        for kw in keywords:
            if kw in slug.lower():
                selected[slug] = info
                break

    print(f"Matched {len(selected)} high-relevance dynamic components:")
    for s, info in selected.items():
        print(f"  - {s}: {info['url']}")

    # Pick top representative dynamic components for full crawling
    targets = [
        'spotlight-card',
        'border-beam',
        'shine-border',
        'animated-tabs',
        'number-ticker',
        'shimmer-button',
        'animated-card',
        'interactive-drawer'
    ]

    # Add available matches
    to_crawl = []
    for slug, info in selected.items():
        to_crawl.append(info)
        if len(to_crawl) >= 15:
            break

    crawled_summary = []

    for item in to_crawl:
        slug = item['slug']
        url = item['url']
        out_dir = os.path.join('.21st-cache', slug)
        os.makedirs(out_dir, exist_ok=True)
        print(f"\n[Crawling 21st.dev] {slug} -> {url}...")
        status, html = fetch(url)
        if status != 200:
            print(f"  Failed: {status}")
            continue

        with open(os.path.join(out_dir, 'page.html'), 'w', encoding='utf-8') as pf:
            pf.write(html)

        # Extract React/Vue/Tailwind code blocks or flight data
        code_blocks = re.findall(r'<pre[^>]*><code[^>]*>(.*?)</code></pre>', html, re.DOTALL)
        snippets = []
        for cb in code_blocks:
            clean = re.sub(r'<[^>]+>', '', cb).strip()
            if len(clean) > 50:
                snippets.append(clean)

        with open(os.path.join(out_dir, 'snippets.txt'), 'w', encoding='utf-8') as sf:
            sf.write("\n\n--- SNIPPET ---\n\n".join(snippets))

        # Extract CSS variables and keyframes
        css_rules = re.findall(r'@keyframes\s+([^{]+)\{[^}]+\}', html)
        css_vars = re.findall(r'(--[a-zA-Z0-9_-]+):', html)

        manifest = {
            'slug': slug,
            'url': url,
            'author': item.get('author'),
            'page_bytes': len(html),
            'snippets_count': len(snippets),
            'css_vars': list(set(css_vars))[:20],
            'keyframes': list(set(css_rules))[:10]
        }
        with open(os.path.join(out_dir, 'manifest.json'), 'w', encoding='utf-8') as mf:
            json.dump(manifest, mf, ensure_ascii=False, indent=2)

        crawled_summary.append(manifest)
        print(f"  Crawled {slug}: {len(html)} bytes, {len(snippets)} code snippets, {len(css_vars)} css vars.")
        time.sleep(0.5)

    with open('reports/21st_crawled_components_summary.json', 'w', encoding='utf-8') as cf:
        json.dump(crawled_summary, cf, ensure_ascii=False, indent=2)

    print(f"\nCrawled {len(crawled_summary)} components successfully into .21st-cache!")

if __name__ == '__main__':
    main()
