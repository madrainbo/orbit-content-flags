# Known sources

`shared/trusted/trusted.tsv` lists the official accounts, websites and titles of notable sources, built from [Wikidata](https://www.wikidata.org) (CC0, so it can be bundled freely). The Android app uses it to decide that flagged content comes from a known source and should be listed but not hidden.

## What is in it

| Category | Wikidata classes (and their subclasses unless noted) | Minimum Wikipedia articles |
|---|---|---|
| news | news media, news agency, newspaper, daily newspaper | 8 |
| broadcaster | broadcaster, TV channel, public broadcaster | 8 |
| government | government agency, government organisation; places and territories removed | 20 |
| international | intergovernmental organisation | 10 |
| university | university (direct instances only) | 25 |
| tv series | television series (direct instances only); accounts, sites and English titles | 12 |

For each item: Instagram (P2003), X (P2002), TikTok (P7085) and YouTube (P11245) usernames, and the official website (P856). Organisations that have ended (P576) are left out, and so are shared platforms such as youtube.com or linktr.ee, which say nothing about who posted.

**Why a minimum number of Wikipedia articles:** anyone can edit Wikidata, so a scammer could add their own handle to an item. Requiring the item to have articles in many Wikipedias limits the list to well-watched items, which makes that harder, though not impossible.

## How the app uses it

- **Who posted, never who is mentioned.** A passage counts as from a known source only if it is on a known site (from the browser's address bar) or the nearest username shown above it on screen is a known account. "BBC says you can double your bitcoin" in a stranger's post is still flagged.
- **Usernames must look like usernames:** shown with "@" (X, YouTube) or in lower case (Instagram, TikTok). Capitalised words such as "People" are interface text. Short plain words shown without "@" (`evil`, `heroes`, `forbes`) could be a one-word comment, so they never count.
- **TV titles** only excuse highly charged wording, not scam, adult or bait flags. One-word titles count only in video apps.
- **The user's own reports always win.**
- **Live lookup:** if the poster of a flagged passage is not in the list, the app can look the username up on Wikidata (settings, on by default). Only the username is sent, at most 20 times an hour, and results are cached on the phone for 30 days. A match needs an item with at least 10 Wikipedia articles that is not a person, has not ended, and lists the username as a current statement.

## Rebuilding

```bash
python3 build.py ../../shared/trusted/trusted.tsv   # about 60 queries; results are cached in shared/trusted/.trusted-cache
```

## Limits

- **Wikidata rate-limits shared addresses.** The build needed retries, and the live API refused requests from this build machine for a while. Lookups from users' phones each come from their own address, but at scale this should move to a server the project runs, holding a copy of the data. Wikimedia's policy also asks automated clients to give contact details in their user agent, which the app does not yet do.
- **Coverage is notable sources only.** Smaller outlets, local news and most creators are not in it.
- **Websites lapse.** An organisation's old domain can be bought by someone else; the list does not check that a site still belongs to the item.
- **Accounts get hacked.** A known account posting a scam will not be hidden.
- **Attribution is a screen-position guess** (nearest username above). In layouts where the poster's name is below or beside the text, it will be wrong.
