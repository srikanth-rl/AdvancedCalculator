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

// ─── Calculate (evaluate full expression) ────────────────────────────────────
async function Calculate() {
  const expression = output.value.trim();
  if (!expression) return;

  setLoading(true);
  try {
    const data = await postForm(EVALUATE_URL, { expression });
    if (data.success) {
      const result = data.result;
      output.value = result;
      output.disabled = false;
      await saveHistory(expression, result);
    } else {
      alert(data.error || 'Invalid input');
    }
  } catch (e) {
    alert('Server error. ');
  } finally {
    setLoading(false);
  }
}

// ─── calculateMod ─────────────────────────────────────────────────────────────
async function calculateMod() {
  output.value = '';
  const num1Str = prompt('To calculate Remainder:\nEnter the first value:');
  if (num1Str === null || num1Str.trim() === '') {
    alert('Invalid input. Please enter a valid number.');
    return;
  }
  const num2Str = prompt('Enter the second value:');
  if (num2Str === null || num2Str.trim() === '') {
    alert('Invalid input. Please enter a valid number.');
    return;
  }

  setLoading(true);
  try {
    const data = await postForm(CALCULATE_URL, {
      action: 'mod',
      num1: num1Str.trim(),
      num2: num2Str.trim(),
    });
    if (data.success) {
      output.value = data.result;
      await saveHistory(`${num1Str} mod ${num2Str}`, data.result);
      document.addEventListener('keydown', clearOutputOnKeyPress);
    } else {
      alert(data.error || 'Invalid input. Please enter a valid number.');
      output.value = '';
    }
  } catch (e) {
    alert('Server error. ');
  } finally {
    setLoading(false);
  }
}

function clearOutputOnKeyPress(event) {
  if (event.key !== 'Enter') {
    output.value = '';
    document.removeEventListener('keydown', clearOutputOnKeyPress);
  }
}

// ─── calculateFactorial ───────────────────────────────────────────────────────
async function calculateFactorial() {
  const val = output.value.trim();
  if (!val) {
    alert('Please enter a number first.');
    return;
  }

  setLoading(true);
  try {
    const data = await postForm(CALCULATE_URL, { action: 'factorial', num1: val });
    if (data.success) {
      // Server returns: "n! = <full result>|digits:<count>"
      const pipeIdx = data.result.lastIndexOf('|digits:');
      const main = pipeIdx !== -1 ? data.result.substring(0, pipeIdx) : data.result;
      const digitsPart = pipeIdx !== -1 ? data.result.substring(pipeIdx + 8) : null;

      // Display full result (e.g. "100000! = 28242294...") in the output field.
      // output is NOT disabled so user can scroll/select the full number.
      output.value = main;
      output.disabled = false;

      const expression = val + '!';

      // For history: show digit count + first 40 chars of the actual number
      const eqIdx = main.indexOf(' = ');
      const numPart = eqIdx !== -1 ? main.substring(eqIdx + 3) : main;

      let histResult;
      if (digitsPart) {
        // Show first 40 digits + "… [N digits total]"
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
      alert(data.error || 'Invalid input.');
    }
  } catch (e) {
    alert('Server error. ');
  } finally {
    setLoading(false);
  }
}

// ─── checkPrime ───────────────────────────────────────────────────────────────
async function checkPrime() {
  let userInput = prompt(
    'Enter a number to check if it is prime\n(up to 50,000 digits supported):'
  );
  if (userInput === null || userInput.trim() === '') {
    alert('Invalid input. Please enter a valid number.');
    return;
  }
  userInput = userInput.replace(/,/g, '').trim();

  setLoading(true);
  try {
    const data = await postForm(CALCULATE_URL, { action: 'prime', num1: userInput });
    if (data.success) {
      alert(data.result);
      const isPrime = data.result.includes('is a prime number');
      await saveHistory(`(${userInput})`, isPrime ? 'Prime number.' : 'Not a Prime number.');
    } else {
      alert(data.error || 'Invalid input. Please enter a valid number.');
    }
  } catch (e) {
    alert('Server error. ');
  } finally {
    setLoading(false);
  }
}

// ─── display / Delete / Clear ─────────────────────────────────────────────────
function display(input) {
  const cursorPos = output.selectionStart;
  output.value = output.value.slice(0, cursorPos) + input + output.value.slice(cursorPos);
  output.setSelectionRange(cursorPos + 1, cursorPos + 1);
  output.focus();
}

function Delete() {
  const cursorPos = output.selectionStart;
  if (cursorPos > 0) {
    output.value = output.value.slice(0, cursorPos - 1) + output.value.slice(cursorPos);
    output.setSelectionRange(cursorPos - 1, cursorPos - 1);
  }
}

function Clear() {
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

// ─── History (server-side session storage) ────────────────────────────────────
async function saveHistory(expression, result) {
  try {
    await postForm(HISTORY_URL, { expression, result });
  } catch (e) {
    // non-critical — silently ignore
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
      // Silently ignore
    }
  }
}

async function clearHistory() {
  try {
    await postForm(HISTORY_URL, { action: 'clear' });
  } catch (e) {
    /* ignore */
  }
  await displayHistory();
}

// ─── Keyboard handler ─────────────────────────────────────────────────────────
document.addEventListener('keydown', function (event) {
  if (event.ctrlKey && event.key === 'r') return;

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
