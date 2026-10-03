(() => {
  const log = document.getElementById('test_log');
  const t = log ? log.innerText : '';
  const r = document.querySelector('#adb_test_r');
  return JSON.stringify({ done: /Total\s*:\s*\d+/.test(t), summary: r ? r.innerText : '',
    score: getComputedStyle(document.body).getPropertyValue('--liquid-title').replace(/'/g, ''), log: t });
})()
