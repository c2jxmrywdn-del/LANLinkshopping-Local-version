import urllib.request
import re
import json
import os
import time

HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/markdown,text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'en-US,en;q=0.9'
}

def fetch_content(url):
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read().decode('utf-8', errors='ignore')
    except Exception as e:
        return None, str(e)

def crawl():
    print("=== Crawling 21st.dev Dynamic UI/UX Suite ===")
    category_tags = ['animated', 'tabs', 'badge', 'accordion', 'card', 'drawer']
    discovered = []

    for tag in category_tags:
        url = f"https://21st.dev/community/components/s/{tag}.md"
        print(f"Fetching category tag: {tag} ({url})...")
        status, content = fetch_content(url)
        if status == 200:
            print(f"  Got markdown for {tag} ({len(content)} chars)")
            # Extract links to components
            links = re.findall(r'\[([^\]]+)\]\((https://21st\.dev/@[^)]+)\)', content)
            for title, comp_url in links:
                if '/components/' in comp_url:
                    discovered.append({
                        'tag': tag,
                        'title': title,
                        'url': comp_url
                    })
        else:
            print(f"  Tag {tag} status {status}")

    print(f"\nTotal components discovered via markdown tags: {len(discovered)}")

    # Specific key dynamic components for our B2B ecommerce & finance & logistics scenarios:
    key_targets = [
        {'slug': 'spotlight-card', 'author': 'easemize', 'category': 'card', 'name': 'Spotlight Glow Card'},
        {'slug': 'shine-border', 'author': 'dillionverma', 'category': 'card', 'name': 'Shine Border Animated Card'},
        {'slug': 'animated-tabs', 'author': 'educalvolpz', 'category': 'tabs', 'name': 'Fluid Sliding Pill Tabs'},
        {'slug': 'sliding-tabs', 'author': 'ruixen.ui', 'category': 'tabs', 'name': 'Elastic Slider Tabs'},
        {'slug': 'number-ticker', 'author': 'dillionverma', 'category': 'number', 'name': 'Number Ticker Spring Counter'},
        {'slug': 'rolling-counter', 'author': 'abushaidislam7', 'category': 'number', 'name': 'Rolling Odometer Counter'},
        {'slug': 'shimmer-button', 'author': 'dillionverma', 'category': 'button', 'name': 'Shimmer Glow Action Button'},
        {'slug': 'radar-pulse', 'author': 'kokonutd', 'category': 'badge', 'name': 'Radar Pulse Live Beacon'},
        {'slug': 'bottom-sheet', 'author': 'f0rextasy', 'category': 'drawer', 'name': 'Mobile Gesture Interactive Bottom Sheet'},
        {'slug': 'smooth-drawer', 'author': 'kokonutd', 'category': 'drawer', 'name': 'Smooth Spring Drawer'}
    ]

    out_base = ".21st-cache"
    os.makedirs(out_base, exist_ok=True)
    crawled_details = []

    for item in key_targets:
        slug = item['slug']
        author = item['author']
        comp_md_url = f"https://21st.dev/@{author}/components/{slug}.md"
        comp_html_url = f"https://21st.dev/@{author}/components/{slug}"
        target_dir = os.path.join(out_base, slug)
        os.makedirs(target_dir, exist_ok=True)

        print(f"\n[Crawling Detail] {slug} by @{author}...")
        status_md, md_text = fetch_content(comp_md_url)
        status_html, html_text = fetch_content(comp_html_url)

        # Save markdown
        if status_md == 200:
            with open(os.path.join(target_dir, 'spec.md'), 'w', encoding='utf-8') as f:
                f.write(md_text)
            print(f"  Saved spec.md ({len(md_text)} chars)")
        else:
            print(f"  Markdown fetch returned {status_md}")

        # Save HTML
        if status_html == 200:
            with open(os.path.join(target_dir, 'page.html'), 'w', encoding='utf-8') as f:
                f.write(html_text)
            print(f"  Saved page.html ({len(html_text)} chars)")

            # Check if there is a preview bundle URL
            bundle_urls = re.findall(r'https://cdn\.21st\.dev/[^\s\"\'<>]+\.html', html_text)
            preview_urls = re.findall(r'https://cdn\.21st\.dev/[^\s\"\'<>]+\.(?:mp4|webp|png)', html_text)

            meta = {
                'slug': slug,
                'author': author,
                'name': item['name'],
                'category': item['category'],
                'md_url': comp_md_url,
                'html_url': comp_html_url,
                'bundle_urls': list(set(bundle_urls))[:3],
                'preview_urls': list(set(preview_urls))[:3],
                'spec_summary': md_text[:300] if status_md == 200 else ""
            }
            crawled_details.append(meta)

            with open(os.path.join(target_dir, 'manifest.json'), 'w', encoding='utf-8') as f:
                json.dump(meta, f, ensure_ascii=False, indent=2)

        time.sleep(0.5)

    os.makedirs('reports', exist_ok=True)
    with open('reports/21st_suite_crawled.json', 'w', encoding='utf-8') as f:
        json.dump(crawled_details, f, ensure_ascii=False, indent=2)

    print(f"\nSuccessfully crawled {len(crawled_details)} dynamic components into {out_base}!")

if __name__ == '__main__':
    crawl()
