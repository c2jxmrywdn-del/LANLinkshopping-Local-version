import re

with open('.21st-cache/spotlight-card/page.html', 'r', encoding='utf-8') as f:
    text = f.read()

# Look for URLs or API routes
urls = re.findall(r'https?://[a-zA-Z0-9.-]+/[a-zA-Z0-9._~:/?#\[\]@!$&\'()*+,;=-]+', text)
internal_urls = [u for u in set(urls) if '21st.dev' in u]
print("Found 21st.dev URLs:")
for u in sorted(internal_urls)[:25]:
    print(" -", u)

# Search for component source or code inside text
matches = re.findall(r'(?:export\s+default|export\s+const|function\s+Spotlight|const\s+Spotlight)[^;\{\}\n]+', text)
print("\nCode tokens found:", len(matches))
for m in matches[:10]:
    print(" token:", m)
