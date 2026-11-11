(async function () {
  const { parseList, MAX_PER_LIST } = globalThis.OCFDomains;
  const $ = (id) => document.getElementById(id);
  const DEFAULTS = {
    lists_block: [], lists_warn: [], lists_allow: [],
    settings: { enabled: true, showMedium: false, blurHigh: false },
  };
  const LISTS = ['warn', 'block', 'allow'];
  const FLAGS = ['enabled', 'showMedium', 'blurHigh'];

  const stored = await chrome.storage.sync.get(DEFAULTS);
  const settings = { ...DEFAULTS.settings, ...stored.settings };
  FLAGS.forEach((k) => { $(k).checked = settings[k]; });
  LISTS.forEach((k) => { $(k).value = stored['lists_' + k].join('\n'); });

  const { disputes = 0 } = await chrome.storage.local.get('disputes');
  $('disputes').textContent = disputes === 1
    ? 'You have disputed 1 flag on this device.'
    : 'You have disputed ' + disputes + ' flags on this device.';

  function status(text, bad) {
    $('status').textContent = text;
    $('status').className = bad ? 'bad' : '';
  }

  $('save').addEventListener('click', async () => {
    const out = { settings: {} };
    FLAGS.forEach((k) => { out.settings[k] = $(k).checked; });
    const invalid = [];
    for (const k of LISTS) {
      const parsed = parseList($(k).value);
      invalid.push(...parsed.invalid);
      if (parsed.valid.length > MAX_PER_LIST) {
        return status('The ' + k + ' list has ' + parsed.valid.length + ' domains; the limit is ' + MAX_PER_LIST + '.', true);
      }
      out['lists_' + k] = parsed.valid;
    }
    if (invalid.length) {
      return status('Not saved. These are not domains: ' + invalid.slice(0, 5).join(', '), true);
    }
    try {
      await chrome.storage.sync.set(out);
      LISTS.forEach((k) => { $(k).value = out['lists_' + k].join('\n'); });
      status('Saved.');
    } catch (e) {
      status('Not saved: ' + e.message, true);
    }
  });
})();
