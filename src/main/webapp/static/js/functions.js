// ─── Context path helper ────────────────────────────────────────────────────
const CTX = '/CalculatorApp';

const EVALUATE_URL = CTX + '/evaluate';
const CALCULATE_URL = CTX + '/calculate';
const HISTORY_URL = CTX + '/history';

// ─── DOM refs ────────────────────────────────────────────────────────────────
let output = document.getElementById('output-screen');

// ─── Loading overlay ─────────────────────────────────────────────────────────
const overlay = document.createElement('div');
overlay.className = 'loading-overlay';
overlay.innerHTML = '<div class="spinner"></div>';
document.body.appendChild(overlay);

function setLoading(on) {
  overlay.classList.toggle('active', on);
}

// ─── Generic fetch helper ─────────────────────────────────────────────────────
async function postForm(url, params) {
  const body = new URLSearchParams(params);
  const res = await fetch(url, { method: 'POST', body });
  return res.json();
}

// =========================================================================
// INLINE MODAL — replaces all prompt() / alert() calls
// =========================================================================

/**
 * Creates a full-screen modal with:
 *  - a large resizable textarea for input (great for pasting 50k-digit numbers)
 *  - an inline error area (errors never erase input)
 *  - confirm / cancel buttons
 *
 * Returns a Promise<string|null>  (null = cancelled)
 *
 * @param {string} title         Modal heading
 * @param {string} placeholder   Textarea placeholder text
 * @param {string} [prefill]     Optional prefill value
 */
function showInputModal(title, placeholder, prefill = '') {
  return new Promise((resolve) => {
    // Backdrop
    const backdrop = document.createElement('div');
    backdrop.className = 'modal-backdrop';

    // Panel
    const panel = document.createElement('div');
    panel.className = 'modal-panel';

    panel.innerHTML = `
      <div class="modal-header">
        <span class="modal-title">${title}</span>
        <button class="modal-close" title="Cancel">✕</button>
      </div>
      <textarea
        class="modal-input"
        placeholder="${placeholder}"
        spellcheck="false"
        autocomplete="off"
        autocorrect="off"
        autocapitalize="off"
      >${prefill}</textarea>
      <div class="modal-error" style="display:none"></div>
      <div class="modal-actions">
        <button class="modal-btn modal-cancel">Cancel</button>
        <button class="modal-btn modal-confirm">OK</button>
      </div>
    `;

    backdrop.appendChild(panel);
    document.body.appendChild(backdrop);

    const textarea = panel.querySelector('.modal-input');
    const errorBox = panel.querySelector('.modal-error');
    const confirmBtn = panel.querySelector('.modal-confirm');
    const cancelBtn = panel.querySelector('.modal-cancel');
    const closeBtn = panel.querySelector('.modal-close');

    // Auto-focus, cursor at end
    setTimeout(() => { textarea.focus(); textarea.setSelectionRange(textarea.value.length, textarea.value.length); }, 50);

    function close(value) {
      backdrop.remove();
      resolve(value);
    }

    confirmBtn.addEventListener('click', () => close(textarea.value));
    cancelBtn.addEventListener('click', () => close(null));
    closeBtn.addEventListener('click', () => close(null));

    // Close on backdrop click (outside panel)
    backdrop.addEventListener('click', (e) => { if (e.target === backdrop) close(null); });

    // Ctrl+Enter to confirm, Escape to cancel
    textarea.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); close(textarea.value); }
      if (e.key === 'Escape') { e.preventDefault(); close(null); }
    });

    // Expose error setter on backdrop so callers can show errors without closing
    backdrop._showError = (msg) => {
      errorBox.textContent = msg;
      errorBox.style.display = msg ? 'block' : 'none';
      textarea.focus();
    };

    backdrop._clearError = () => {
      errorBox.textContent = '';
      errorBox.style.display = 'none';
    };
  });
}

/**
 * Two-step modal: asks for two values sequentially.
 * Returns Promise<[string, string]|null>
 */
async function showTwoInputModal(title1, placeholder1, title2, placeholder2) {
  const v1 = await showInputModal(title1, placeholder1);
  if (v1 === null) return null;
  const v2 = await showInputModal(title2, placeholder2);
  if (v2 === null) return null;
  return [v1.trim(), v2.trim()];
}

/**
 * Inline notification banner (non-blocking, appears above the calculator).
 * type: 'error' | 'info' | 'success'
 * If keepContent is true the output field is NOT modified on error.
 */
function showNotice(message, type = 'error') {
  // Remove any existing notice
  document.querySelectorAll('.calc-notice').forEach(el => el.remove());

  const notice = document.createElement('div');
  notice.className = `calc-notice calc-notice--${type}`;
  notice.textContent = message;

  // Insert above the calculator div
  const calc = document.querySelector('.calculator');
  if (calc) calc.parentNode.insertBefore(notice, calc);

  // Auto-dismiss after 6 seconds for non-errors
  if (type !== 'error') setTimeout(() => notice.remove(), 6000);
}

function clearNotice() {
  document.querySelectorAll('.calc-notice').forEach(el => el.remove());
}

// =========================================================================
// CALCULATOR OPERATIONS
// =========================================================================

// ─── Calculate (evaluate full expression) ────────────────────────────────────
async function Calculate() {
  const expression = output.value.trim();
  if (!expression) return;

  clearNotice();
  setLoading(true);
  try {
    const data = await postForm(EVALUATE_URL, { expression });
    if (data.success) {
      const result = data.result;
      output.value = result;
      output.disabled = false;
      await saveHistory(expression, result);
    } else {
      // Error: show inline notice, DO NOT erase output
      showNotice(data.error || 'Invalid input', 'error');
    }
  } catch (e) {
    showNotice('Server error — please try again.', 'error');
  } finally {
    setLoading(false);
  }
}

// ─── calculateMod ─────────────────────────────────────────────────────────────
async function calculateMod() {
  const values = await showTwoInputModal(
    'Remainder — Enter first value',
    'e.g. 1000000000',
    'Remainder — Enter second value',
    'e.g. 7'
  );
  if (!values) return;
  const [num1Str, num2Str] = values;
  if (!num1Str || !num2Str) {
    showNotice('Invalid input. Please enter valid numbers.', 'error');
    return;
  }

  setLoading(true);
  try {
    const data = await postForm(CALCULATE_URL, {
      action: 'mod',
      num1: num1Str,
      num2: num2Str,
    });
    if (data.success) {
      output.value = data.result;
      output.disabled = false;
      await saveHistory(`${num1Str} mod ${num2Str}`, data.result);
      clearNotice();
    } else {
      // Error: show notice, leave output intact
      showNotice(data.error || 'Invalid input. Please enter valid numbers.', 'error');
    }
  } catch (e) {
    showNotice('Server error — please try again.', 'error');
  } finally {
    setLoading(false);
  }
}

// ─── calculateFactorial ───────────────────────────────────────────────────────
async function calculateFactorial() {
  const val = output.value.trim();
  if (!val) {
    showNotice('Please enter a number first.', 'error');
    return;
  }

  clearNotice();
  setLoading(true);
  try {
    const data = await postForm(CALCULATE_URL, { action: 'factorial', num1: val });
    if (data.success) {
      const pipeIdx = data.result.lastIndexOf('|digits:');
      const main = pipeIdx !== -1 ? data.result.substring(0, pipeIdx) : data.result;
      const digitsPart = pipeIdx !== -1 ? data.result.substring(pipeIdx + 8) : null;

      output.value = main;
      output.disabled = false;

      const expression = val + '!';
      const eqIdx = main.indexOf(' = ');
      const numPart = eqIdx !== -1 ? main.substring(eqIdx + 3) : main;

      let histResult;
      if (digitsPart) {
        const preview =
          numPart.length > 40
            ? numPart.substring(0, 40) + '… [' + digitsPart + ' digits total]'
            : numPart + ' [' + digitsPart + ' digits]';
        histResult = preview;
      } else {
        histResult = numPart.length > 60 ? numPart.substring(0, 60) + '…' : numPart;
      }

      await saveHistory(expression, histResult);
    } else {
      // Error: show notice, DO NOT erase output
      showNotice(data.error || 'Invalid input.', 'error');
    }
  } catch (e) {
    showNotice('Server error — please try again.', 'error');
  } finally {
    setLoading(false);
  }
}

// ─── checkPrime ───────────────────────────────────────────────────────────────
/**
 * Opens a large modal input so users can paste up to 50k-digit numbers easily.
 * Result is shown as an inline notice, not an alert.
 */
async function checkPrime() {
  const userInput = await showInputModal(
    'Check Prime Number',
    'Paste or type a number here — up to 15,000 digits supported.\nCtrl+Enter to confirm.',
    ''
  );
  if (userInput === null) return;

  const cleaned = userInput.replace(/,/g, '').trim();
  if (!cleaned) {
    showNotice('Invalid input. Please enter a valid number.', 'error');
    return;
  }

  setLoading(true);
  try {
    const data = await postForm(CALCULATE_URL, { action: 'prime', num1: cleaned });
    if (data.success) {
      const isPrime = data.result.includes('is a prime number');
      showNotice(data.result, isPrime ? 'success' : 'info');
      const displayNum = cleaned.length > 20 ? cleaned.substring(0, 20) + '…' : cleaned;
      await saveHistory(`Prime(${displayNum})`, isPrime ? 'Prime number.' : 'Not a Prime number.');
    } else {
      // Error: show notice (input was already in modal, nothing to erase)
      showNotice(data.error || 'Invalid input. Please enter a valid number.', 'error');
    }
  } catch (e) {
    showNotice('Server error — please try again.', 'error');
  } finally {
    setLoading(false);
  }
}

// ─── display / Delete / Clear ─────────────────────────────────────────────────
function display(input) {
  clearNotice();
  const cursorPos = output.selectionStart;
  output.value = output.value.slice(0, cursorPos) + input + output.value.slice(cursorPos);
  output.setSelectionRange(cursorPos + 1, cursorPos + 1);
  output.focus();
}

function Delete() {
  clearNotice();
  const cursorPos = output.selectionStart;
  if (cursorPos > 0) {
    output.value = output.value.slice(0, cursorPos - 1) + output.value.slice(cursorPos);
    output.setSelectionRange(cursorPos - 1, cursorPos - 1);
  }
}

function Clear() {
  clearNotice();
  output.value = '';
  output.disabled = false;
  output.focus();
}

// ─── toggleNotes ──────────────────────────────────────────────────────────────
function toggleNotes() {
  const notesList = document.getElementById('notes');
  if (notesList.style.display === 'none' || notesList.style.display === '') {
    notesList.style.display = 'block';
  } else {
    notesList.style.display = 'none';
  }
}

// ─── toggleDarkMode ───────────────────────────────────────────────────────────
function toggleDarkMode() {
  const body = document.body;
  body.classList.toggle('dark-mode');
  const btn = document.querySelector('.toggle-mode.dark-mode');
  if (btn) {
    btn.textContent = body.classList.contains('dark-mode') ? 'Light Mode' : 'Dark Mode';
  }
}

// ─── History ──────────────────────────────────────────────────────────────────
async function saveHistory(expression, result) {
  try {
    await postForm(HISTORY_URL, { expression, result });
  } catch (e) {
    // non-critical
  }
}

async function displayHistory() {
  const historyBar = document.getElementById('history-bar');
  historyBar.classList.toggle('visible');

  if (historyBar.classList.contains('visible')) {
    try {
      const data = await fetch(HISTORY_URL).then(r => r.json());
      const clearBtn = historyBar.querySelector('.clear-history-btn');
      historyBar.innerHTML = '';
      if (clearBtn) historyBar.appendChild(clearBtn);

      if (data.success && data.history.length > 0) {
        for (const entry of data.history) {
          const div = document.createElement('div');
          div.textContent = `${entry.expression} = ${entry.result}`;
          historyBar.insertBefore(div, clearBtn);
        }
      } else {
        const empty = document.createElement('div');
        empty.textContent = 'No history yet.';
        historyBar.insertBefore(empty, clearBtn);
      }
      historyBar.scrollTop = historyBar.scrollHeight;
    } catch (e) {
      // silently ignore
    }
  }
}

async function clearHistory() {
  try {
    await postForm(HISTORY_URL, { action: 'clear' });
  } catch (e) { /* ignore */ }
  await displayHistory();
}

// ─── Keyboard handler ─────────────────────────────────────────────────────────
document.addEventListener('keydown', function (event) {
  if (event.ctrlKey && event.key === 'r') return;

  // Don't intercept when a modal is open
  if (document.querySelector('.modal-backdrop')) return;

  if (output.disabled && !event.ctrlKey) {
    event.preventDefault();
    return;
  }

  if (
    (event.key >= '0' && event.key <= '9') ||
    ['+', '-', '*', '/', '%'].includes(event.key) ||
    event.key === 'Enter' ||
    event.key.toLowerCase() === 'f' ||
    event.key === 'Backspace'
  ) {
    if (event.key === 'Enter') {
      event.preventDefault();
      Calculate();
    } else if (event.key.toLowerCase() === 'f') {
      event.preventDefault();
      calculateFactorial();
    } else if (event.key === 'Backspace' && event.ctrlKey) {
      event.preventDefault();
      Clear();
    } else if (event.key === 'Backspace') {
      event.preventDefault();
      const cursorPos = output.selectionStart;
      if (cursorPos > 0) {
        output.value = output.value.slice(0, cursorPos - 1) + output.value.slice(cursorPos);
        output.setSelectionRange(cursorPos - 1, cursorPos - 1);
      }
    } else {
      event.preventDefault();
      clearNotice();
      const cursorPos = output.selectionStart;
      output.value = output.value.slice(0, cursorPos) + event.key + output.value.slice(cursorPos);
      output.setSelectionRange(cursorPos + 1, cursorPos + 1);
    }
  } else if (event.key.toLowerCase() === 'h') {
    event.preventDefault();
    displayHistory();
  } else if (event.key === 'ArrowLeft') {
    event.preventDefault();
    const pos = output.selectionStart;
    output.setSelectionRange(Math.max(0, pos - 1), Math.max(0, pos - 1));
  } else if (event.key === 'ArrowRight') {
    event.preventDefault();
    const pos = output.selectionStart;
    const max = output.value.length;
    output.setSelectionRange(Math.min(max, pos + 1), Math.min(max, pos + 1));
  }
});

// ─── Clock ────────────────────────────────────────────────────────────────────
function clock() {
  const dt = new Date();
  let hr = dt.getHours();
  const min = dt.getMinutes();
  const sec = dt.getSeconds();
  const day = dt.toLocaleDateString('en-US', { weekday: 'long' });
  const month = dt.toLocaleDateString('en-US', { month: 'long' });
  const date = dt.getDate();

  document.getElementById('ampm').innerHTML = hr >= 12 ? 'PM' : 'AM';
  if (hr > 12) hr -= 12;
  if (hr === 0) hr = 12;

  document.getElementById('hrs').innerHTML = addZero(hr);
  document.getElementById('min').innerHTML = addZero(min);
  document.getElementById('sec').innerHTML = addZero(sec);
  document.getElementById('day').innerHTML = day;
  document.getElementById('month').innerHTML = month;
  document.getElementById('date').innerHTML = date;
}

setInterval(clock, 1000);

function addZero(no) {
  return no < 10 ? '0' + no : no;
}