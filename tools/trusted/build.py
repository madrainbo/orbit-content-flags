"""Builds shared/trusted.tsv: official accounts, sites and titles of notable
news organisations, broadcasters, public bodies and TV series, from Wikidata
(CC0). An item must have articles in at least MIN_SITELINKS Wikipedias, which
makes the list hard to game by editing Wikidata.

Usage: python3 build.py ../../shared/trusted/trusted.tsv"""
import hashlib, json, os, sys, time, urllib.error, urllib.parse, urllib.request
from urllib.parse import urlparse

ENDPOINT = 'https://query.wikidata.org/sparql'
AGENT = 'orbit-content-flags-build/0.1 (trusted-source list; offline build)'

# category: (Wikidata classes, include subclasses, minimum sitelinks)
CATEGORIES = {
    'news':       (['Q1193236', 'Q192283', 'Q11032', 'Q1110794'], True, 8),   # news media, agency, newspaper, daily
    'broadcaster':(['Q15265344', 'Q2001305', 'Q1126006'], True, 8),          # broadcaster, TV channel, public broadcaster
    'government': (['Q327333', 'Q2659904'], True, 20),                       # government agency, government organisation
    'international': (['Q245065'], True, 10),                                # intergovernmental organisation
    'university': (['Q3918'], False, 25),                                    # university (direct instances only)
    'tv series':  (['Q5398426'], False, 12),                                 # television series (direct instances)
}
PROPS = {'P2003': 'ACCOUNT', 'P2002': 'ACCOUNT', 'P7085': 'ACCOUNT', 'P11245': 'ACCOUNT', 'P856': 'DOMAIN'}

CACHE = os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])), '.trusted-cache')

def query(sparql):
    """Runs a query, caching results on disk so an interrupted build can resume."""
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, hashlib.sha256(sparql.encode()).hexdigest()[:20] + '.json')
    if os.path.exists(path):
        return json.load(open(path))
    data = urllib.parse.urlencode({'query': sparql, 'format': 'json'}).encode()
    for attempt in range(6):
        req = urllib.request.Request(ENDPOINT, data=data, headers={
            'User-Agent': AGENT, 'Accept': 'application/sparql-results+json',
            'Content-Type': 'application/x-www-form-urlencoded'})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                rows = json.load(r)['results']['bindings']
            json.dump(rows, open(path, 'w'))
            return rows
        except urllib.error.HTTPError as e:
            wait = int(e.headers.get('Retry-After') or 0) or 20 * (attempt + 1)
            print(f'  HTTP {e.code}, waiting {wait}s', file=sys.stderr)
            time.sleep(wait)
        except Exception as e:
            print('  retry after', type(e).__name__, file=sys.stderr)
            time.sleep(20 * (attempt + 1))
    print('  giving up on one query; it is left out of the list', file=sys.stderr)
    return []

def normalise(kind, value):
    v = value.strip()
    if kind == 'DOMAIN':
        host = (urlparse(v if '://' in v else 'https://' + v).hostname or '').lower()
        if host.startswith('www.'): host = host[4:]
        return host if '.' in host else None
    v = v.lstrip('@').lower()
    return v if 2 <= len(v) <= 40 and all(c.isalnum() or c in '._' for c in v) else None

rows = {}
for category, (classes, sub, min_links) in CATEGORIES.items():
    for cls in classes:
        path = 'wdt:P31/wdt:P279*' if sub else 'wdt:P31'
        for prop, kind in PROPS.items():
            sparql = f'''SELECT ?item ?label ?v WHERE {{
              ?item {path} wd:{cls} ; wikibase:sitelinks ?n . FILTER(?n >= {min_links})
              FILTER NOT EXISTS {{ ?item wdt:P576 ?ended }}
              ?item wdt:{prop} ?v .
              OPTIONAL {{ ?item rdfs:label ?label FILTER(LANG(?label) = "en") }}
            }}'''
            got = query(sparql)
            kept = 0
            for b in got:
                value = normalise(kind, b['v']['value'])
                if not value: continue
                label = b.get('label', {}).get('value') or b['item']['value'].rsplit('/', 1)[-1]
                key = (kind, value)
                if key not in rows:
                    rows[key] = (label, category, b['item']['value'].rsplit('/', 1)[-1])
                    kept += 1
            print(f'{category:13s} {cls:10s} {prop:6s} {len(got):6d} results, {kept:6d} new', file=sys.stderr)
            time.sleep(3)
    if category == 'tv series':
        got = query(f'''SELECT ?item ?label WHERE {{
          ?item wdt:P31 wd:Q5398426 ; wikibase:sitelinks ?n . FILTER(?n >= {min_links})
          ?item rdfs:label ?label FILTER(LANG(?label) = "en") }}''')
        kept = 0
        for b in got:
            title = b['label']['value'].strip()
            if len(title) < 4: continue
            key = ('TITLE', title.lower())
            if key not in rows:
                rows[key] = (title, category, b['item']['value'].rsplit('/', 1)[-1]); kept += 1
        print(f'{category:13s} titles            {len(got):6d} results, {kept:6d} new', file=sys.stderr)

# Local councils are filed under government on Wikidata; towns are not the
# public bodies meant here, and small-town websites lapse and get re-registered.
# Drop any public body that is also an instance of a place or territory.
PLACE_ROOTS = ['Q486972', 'Q56061']   # human settlement, administrative territorial entity
place_classes = set()
for root in PLACE_ROOTS:
    for b in query(f'SELECT ?c WHERE {{ ?c wdt:P279* wd:{root} }}'):
        place_classes.add(b['c']['value'].rsplit('/', 1)[-1])
print('place classes:', len(place_classes), file=sys.stderr)
public = sorted({qid for (label, category, qid) in rows.values() if category == 'government'})
places = set()
for i in range(0, len(public), 300):
    ids = ' '.join('wd:' + q for q in public[i:i + 300])
    for b in query(f'SELECT ?item ?cls WHERE {{ VALUES ?item {{ {ids} }} ?item wdt:P31 ?cls }}'):
        if b['cls']['value'].rsplit('/', 1)[-1] in place_classes:
            places.add(b['item']['value'].rsplit('/', 1)[-1])
    time.sleep(2)
before = len(rows)
rows = {k: v for k, v in rows.items() if not (v[1] == 'government' and v[2] in places)}
print(f'dropped {before - len(rows)} entries for {len(places)} places', file=sys.stderr)

# Shared platforms are not sources: a link to youtube.com says nothing about who posted.
PLATFORMS = {'youtube.com', 'facebook.com', 'instagram.com', 'twitter.com', 'x.com', 'tiktok.com', 'linkedin.com',
             'google.com', 'apple.com', 'wikipedia.org', 'blogspot.com', 'wordpress.com', 'medium.com', 'github.io',
             'bit.ly', 't.co', 'linktr.ee', 'substack.com', 'twitch.tv', 'reddit.com', 'whatsapp.com', 't.me'}
with open(sys.argv[1], 'w') as f:
    f.write('# type\tvalue\tlabel\tcategory\twikidata  (generated by tools/trusted/build.py from Wikidata, CC0)\n')
    for (kind, value), (label, category, qid) in sorted(rows.items()):
        if kind == 'DOMAIN' and (value in PLATFORMS or any(value.endswith('.' + p) for p in PLATFORMS)): continue
        f.write(f'{kind}\t{value}\t{label.replace(chr(9), " ")}\t{category}\t{qid}\n')
print('wrote', sys.argv[1], file=sys.stderr)
