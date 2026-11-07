import urllib.request
import re
import json
import os
import sys

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9'
}

def fetch_url(url):
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            return resp.status, resp.read().decode('utf-8', errors='ignore')
    except Exception as e:
        return None, str(e)

def find_components():
    seeds = [
        'https://21st.dev',
        'https://21st.dev/s/animated',
        'https://21st.dev/s/card',
        'https://21st.dev/s/tabs',
        'https://21st.dev/s/button',
        'https://21st.dev/s/number',
        'https://21st.dev/s/drawer',
        'https://21st.dev/s/calculator',
    ]

    all_components = {}

    for seed in seeds:
        print(f"Fetching {seed}...")
        status, html = fetch_url(seed)
        if status != 200:
            print(f"Failed {seed}: {html[:100]}")
            continue

        # Find component urls like /@author/components/slug or /@author/slug
        matches = re.findall(r'href=["\'](/@[^"\']+/(?:components/)?[^"\'?#]+)["\']', html)
        for m in matches:
            parts = m.strip('/').split('/')
            if len(parts) >= 2:
                author = parts[0].replace('@', '')
                slug = parts[-1]
                full_path = f"/@{author}/components/{slug}"
                if slug not in all_components and slug not in ['components', 'overview', 'collections']:
                    all_components[slug] = {
                        'author': author,
                        'slug': slug,
                        'url': f"https://21st.dev{m}"
                    }

    print(f"\nFound {len(all_components)} components on 21st.dev!")
    for slug, info in list(all_components.items())[:20]:
        print(f" - {slug}: {info['url']}")

    os.makedirs('reports', exist_ok=True)
    with open('reports/21st_components_discovered.json', 'w', encoding='utf-8') as f:
        json.dump(all_components, f, ensure_ascii=False, indent=2)

if __name__ == '__main__':
    find_components()
