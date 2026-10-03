(() => {
  const text = document.body ? document.body.innerText : '';
  const done = /\d+\s*points?\s*out of\s*\d+/i.test(text) && !/checking|loading|in progress/i.test(text.slice(0, 4000));
  return JSON.stringify({ done, text: text.slice(0, 30000) });
})()
