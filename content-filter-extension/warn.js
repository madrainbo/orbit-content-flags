(function () {
  const mode = new URLSearchParams(location.search).get('mode') === 'block' ? 'block' : 'warn';
  const raw = location.hash.slice(1);
  let target = null;
  try {
    const u = new URL(raw);
    if (u.protocol === 'http:' || u.protocol === 'https:') target = u;
  } catch { /* leave null */ }

  const $ = (id) => document.getElementById(id);
  const host = target ? target.hostname : 'This site';
  $('title').textContent = mode === 'block'
    ? host + ' is on your block list'
    : host + ' is on your warn list';
  $('detail').textContent = mode === 'block'
    ? 'You chose to block this site. Remove it from the list to visit it.'
    : 'You asked to be warned before opening this site.';

  $('back').addEventListener('click', () => {
    if (history.length > 1) history.back(); else window.close();
  });
  $('settings').addEventListener('click', () => chrome.runtime.openOptionsPage());

  if (mode === 'warn' && target) {
    const btn = $('proceed');
    btn.hidden = false;
    btn.addEventListener('click', async () => {
      const res = await chrome.runtime.sendMessage({ type: 'proceed', url: target.href });
      if (res && res.ok) location.replace(target.href);
      else $('detail').textContent = 'Could not continue: this site is not on your warn list.';
    });
  }
})();
