// ─── Context path ─────────────────────────────────────────────────────────────
const CTX = '/CalculatorApp';
const EVALUATE_URL = CTX + '/evaluate';
const CALCULATE_URL = CTX + '/calculate';
const HISTORY_URL = CTX + '/history';

// ─── DOM refs ─────────────────────────────────────────────────────────────────
const output = document.getElementById('output-screen');
let currentAbortController = null;

// ─── History State & Cache ────────────────────────────────────────────────────
let historyCache = null;
let isHistoryDirty = true;
let isHistoryLoading = false;

// ─── Request lock ─────────────────────────────────────────────────────────────
let isRequestInProgress = false;

function setRequestLock(v) {
    isRequestInProgress = v;
}

function isRequestLocked() {
    return isRequestInProgress;
}

// ─── Loading overlay ──────────────────────────────────────────────────────────
const overlay = document.createElement('div');
overlay.className = 'loading-overlay';
overlay.innerHTML = '<div class="spinner"></div>';
document.body.appendChild(overlay);

function setLoading(on) {
    overlay.classList.toggle('active', on);
}

// ─── Generic POST ─────────────────────────────────────────────────────────────
async function postForm(url, params) {
    currentAbortController = new AbortController();
    const res = await fetch(url, {
        method: 'POST',
        body: new URLSearchParams(params),
        signal: currentAbortController.signal
    });
    return res.json();
}

// =========================================================================
// INLINE MODAL
// =========================================================================

function showInputModal(title, placeholder, prefill = '') {
    return new Promise((resolve) => {
        const backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        const panel = document.createElement('div');
        panel.className = 'modal-panel';
        panel.innerHTML = `
            <div class="modal-header">
                <span class="modal-title">${title}</span>
                <button class="modal-close" title="Cancel">✕</button>
            </div>
            <textarea class="modal-input" placeholder="${placeholder}"
                spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off"
            >${prefill}</textarea>
            <div class="modal-error" style="display:none"></div>
            <div class="modal-actions">
                <button class="modal-btn modal-cancel">Cancel</button>
                <button class="modal-btn modal-confirm">OK</button>
            </div>`;

        backdrop.appendChild(panel);
        document.body.appendChild(backdrop);

        const textarea = panel.querySelector('.modal-input');
        const errorBox = panel.querySelector('.modal-error');
        const confirmBtn = panel.querySelector('.modal-confirm');
        const cancelBtn = panel.querySelector('.modal-cancel');
        const closeBtn = panel.querySelector('.modal-close');

        setTimeout(() => {
            textarea.focus();
            textarea.setSelectionRange(textarea.value.length, textarea.value.length);
        }, 50);

        function close(value) {
            backdrop.remove();
            resolve(value);
        }

        confirmBtn.addEventListener('click', () => close(textarea.value));
        cancelBtn.addEventListener('click', () => close(null));
        closeBtn.addEventListener('click', () => close(null));
        backdrop.addEventListener('click', (e) => {
            if (e.target === backdrop) {
                close(null);
            }
        });
        textarea.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && e.ctrlKey) {
                e.preventDefault();
                close(textarea.value);
            }
            if (e.key === 'Escape') {
                e.preventDefault();
                close(null);
            }
        });
        backdrop._showError = (msg) => {
            errorBox.textContent = msg;
            errorBox.style.display = msg ? 'block' : 'none';
            textarea.focus();
        };
    });
}

async function showTwoInputModal(t1, p1, t2, p2) {
    const v1 = await showInputModal(t1, p1);
    if (v1 === null) {
        return null;
    }
    const v2 = await showInputModal(t2, p2);
    if (v2 === null) {
        return null;
    }
    return [v1.trim(), v2.trim()];
}

function showNotice(message, type = 'error') {
    document.querySelectorAll('.calc-notice').forEach(el => el.remove());
    const notice = document.createElement('div');
    notice.className = `calc-notice calc-notice--${type}`;
    notice.textContent = message;
    const calc = document.querySelector('.calculator');
    if (calc) {
        calc.parentNode.insertBefore(notice, calc);
    }
    if (type !== 'error') {
        setTimeout(() => notice.remove(), 6000);
    }
}

function clearNotice() {
    document.querySelectorAll('.calc-notice').forEach(el => el.remove());
}

// =========================================================================
// CALCULATOR OPERATIONS
// =========================================================================

async function Calculate() {
    const expression = output.value.trim();
    if (!expression || isRequestLocked()) {
        return;
    }

    const hasOperator = /[+\-*/%^]/.test(expression) || expression.includes('**');
    if (!hasOperator) {
        clearNotice();
        output.scrollLeft = output.scrollWidth;
        return;
    }

    if (historyCache) {
        const cachedEntry = historyCache.find(e => e.expression === expression);
        if (cachedEntry) {
            output.value = cachedEntry.result;
            output.scrollLeft = output.scrollWidth;
            return;
        }
    }

    clearNotice();
    setRequestLock(true);
    setLoading(true);

    try {
        const data = await postForm(EVALUATE_URL, { expression });
        if (data.success) {
            output.value = data.result;
            output.scrollLeft = output.scrollWidth;
            await saveHistory(expression, data.result, data.digitLength);
        } else {
            showNotice(data.error || 'Invalid input');
        }
    } catch (e) {
        if (e.name !== 'AbortError') {
            showNotice('Server error — please try again.');
        }
    } finally {
        setLoading(false);
        setRequestLock(false);
    }
}

async function calculateMod() {
    if (isRequestLocked()) {
        return;
    }
    const values = await showTwoInputModal(
        'Remainder — Enter first value', 'e.g. 10',
        'Remainder — Enter second value', 'e.g. 7'
    );
    if (!values) {
        return;
    }
    const [num1Str, num2Str] = values;
    setRequestLock(true);
    setLoading(true);
    try {
        const data = await postForm(CALCULATE_URL, { action: 'mod', num1: num1Str, num2: num2Str });
        if (data.success) {
            output.value = data.result;
            output.disabled = false;
            await saveHistory(`${num1Str} mod ${num2Str}`, data.result, data.digitLength);
        } else {
            showNotice(data.error || 'Invalid input.');
        }
    } catch (e) {
        if (e.name !== 'AbortError') {
            showNotice('Server error.');
        }
    } finally {
        setLoading(false);
        setRequestLock(false);
    }
}

async function calculateFactorial() {
    const val = output.value.trim();
    if (!val || isRequestLocked()) {
        return;
    }
    setRequestLock(true);
    setLoading(true);
    try {
        const data = await postForm(CALCULATE_URL, { action: 'factorial', num1: val });
        if (data.success) {
            output.value = data.result;
            output.disabled = false;
            await saveHistory(`${val}!`, data.result, data.digitLength);
        } else {
            showNotice(data.error || 'Invalid input.');
        }
    } catch (e) {
        if (e.name !== 'AbortError') {
            showNotice('Server error.');
        }
    } finally {
        setLoading(false);
        setRequestLock(false);
    }
}

async function checkPrime() {
    if (isRequestLocked()) {
        return;
    }
    const userInput = await showInputModal('Check Prime Number', 'Enter a number');
    if (userInput === null) {
        return;
    }
    const cleaned = userInput.replace(/,/g, '').trim();
    setRequestLock(true);
    setLoading(true);
    try {
        const data = await postForm(CALCULATE_URL, { action: 'prime', num1: cleaned });
        if (data.success) {
            showNotice(data.result, data.result.startsWith('A') ? 'success' : 'info');
            await saveHistory(`checkPrime{${cleaned}}`, data.result, cleaned.length, true);
        } else {
            showNotice(data.error || 'Invalid input.');
        }
    } catch (e) {
        if (e.name !== 'AbortError') {
            showNotice('Server error.');
        }
    } finally {
        setLoading(false);
        setRequestLock(false);
    }
}

// ─── display / Delete / Clear ─────────────────────────────────────────────────
function display(input) {
    clearNotice();
    const pos = output.selectionStart;
    output.value = output.value.slice(0, pos) + input + output.value.slice(pos);
    const newPos = pos + input.length;
    output.setSelectionRange(newPos, newPos);
    output.focus();
    output.scrollLeft = output.scrollWidth;
}

function Delete() {
    clearNotice();
    const pos = output.selectionStart;
    if (pos > 0) {
        output.value = output.value.slice(0, pos - 1) + output.value.slice(pos);
        output.setSelectionRange(pos - 1, pos - 1);
    }
    output.scrollLeft = output.scrollWidth;
}

function Clear() {
    clearNotice();
    output.value = '';
    output.disabled = false;
    output.focus();
}

// ─── UI toggles ───────────────────────────────────────────────────────────────
function toggleNotes() {
    const el = document.getElementById('notes');
    el.style.display = (el.style.display === 'none' || el.style.display === '') ? 'block' : 'none';
}

function toggleDarkMode() {
    document.body.classList.toggle('dark-mode');
    const btn = document.querySelector('.toggle-mode.dark-mode');
    if (btn) {
        btn.textContent = document.body.classList.contains('dark-mode') ? 'Light Mode' : 'Dark Mode';
    }
}

// ─── History Logic ────────────────────────────────────────────────────────────
async function saveHistory(expression, result, digitLength, isPrime = false) {
    const digitsLength = isPrime ? `${digitLength} digits` : (digitLength > 0 ? `${digitLength} digits` : '');
    try {
        const res = await fetch(HISTORY_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ expression, result, digitsLength })
        });
        const data = await res.json();
        if (data.success) {
            isHistoryDirty = true;
        }
    } catch (e) {
        console.error('History sync failed:', e);
    }
}

function formatHistoryDisplay(expression, result, digitsLength) {
    const digitLine = digitsLength ? `\n[${digitsLength}]` : '';
    return `${expression} =\n${result}${digitLine}`;
}

async function displayHistory() {
    const historyBar = document.getElementById('history-bar');
    historyBar.classList.toggle('visible');
    if (!historyBar.classList.contains('visible')) {
        return;
    }

    if (!isHistoryDirty && historyCache) {
        renderHistory(historyCache);
        return;
    }

    if (isHistoryLoading) {
        return;
    }
    isHistoryLoading = true;

    try {
        const data = await fetch(HISTORY_URL).then(r => r.json());
        if (data.success) {
            historyCache = data.history;
            isHistoryDirty = false;
            renderHistory(historyCache);
        }
    } catch (e) {
        console.error('History fetch error:', e);
    } finally {
        isHistoryLoading = false;
    }
}

function renderHistory(historyArray) {
    const historyBar = document.getElementById('history-bar');
    const clearBtn = historyBar.querySelector('.clear-history-btn');
    historyBar.innerHTML = '';
    if (clearBtn) {
        historyBar.appendChild(clearBtn);
    }

    if (historyArray && historyArray.length > 0) {
        historyArray.forEach(entry => {
            const itemDiv = document.createElement('div');
            itemDiv.className = 'history-item';
            itemDiv.innerHTML = `
                <span class="history-content">${formatHistoryDisplay(entry.expression, entry.result, entry.digitsLength)}</span>
                <button class="copy-history-btn">📋</button>`;

            const btn = itemDiv.querySelector('.copy-history-btn');
            btn.onclick = (e) => {
                e.stopPropagation();
                navigator.clipboard.writeText(entry.result).then(() => {
                    const old = btn.innerHTML;
                    btn.innerHTML = '✅';
                    setTimeout(() => btn.innerHTML = old, 1500);
                });
            };
            historyBar.insertBefore(itemDiv, clearBtn);
            setTimeout(() => { itemDiv.scrollTop = itemDiv.scrollHeight; }, 10);
        });
    } else {
        const empty = document.createElement('div');
        empty.className = 'history-item';
        empty.textContent = 'No history yet.';
        historyBar.insertBefore(empty, clearBtn);
    }
    historyBar.scrollTop = historyBar.scrollHeight;
}

async function clearHistory() {
    try {
        await postForm(HISTORY_URL, { action: 'clear' });
        historyCache = [];
        isHistoryDirty = false;
        renderHistory([]);
    } catch {
        // ignore
    }
}

// ─── Keyboard handler ─────────────────────────────────────────────────────────
document.addEventListener('keydown', (event) => {
    if (event.ctrlKey && event.key === 'r') {
        return;
    }
    if (document.querySelector('.modal-backdrop')) {
        return;
    }
    if (output.disabled && !event.ctrlKey) {
        event.preventDefault();
        return;
    }

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
        Delete();
    } else if ((event.key >= '0' && event.key <= '9') || ['+', '-', '*', '/', '%'].includes(event.key)) {
        event.preventDefault();
        display(event.key);
    } else if (event.key.toLowerCase() === 'h') {
        event.preventDefault();
        displayHistory();
    }
});

// ─── Clock ────────────────────────────────────────────────────────────────────
function clock() {
    const dt = new Date();
    let hr = dt.getHours();
    document.getElementById('ampm').innerHTML = hr >= 12 ? 'PM' : 'AM';
    if (hr > 12) {
        hr -= 12;
    }
    if (hr === 0) {
        hr = 12;
    }
    document.getElementById('hrs').innerHTML = addZero(hr);
    document.getElementById('min').innerHTML = addZero(dt.getMinutes());
    document.getElementById('sec').innerHTML = addZero(dt.getSeconds());
    document.getElementById('day').innerHTML = dt.toLocaleDateString('en-US', { weekday: 'long' });
    document.getElementById('month').innerHTML = dt.toLocaleDateString('en-US', { month: 'long' });
    document.getElementById('date').innerHTML = dt.getDate();
}
setInterval(clock, 1000);

function addZero(n) {
    return n < 10 ? '0' + n : n;
}

// ─── Page load ────────────────────────────────────────────────────────────────
window.addEventListener('load', async () => {
    try {
        await fetch(CALCULATE_URL + '?action=ping&forceReset=true', { method: 'POST' });
        setRequestLock(false);
        setLoading(false);
        console.log('Session lock synchronized with server.');
    } catch (e) {
        console.warn('Initial server sync failed. Server might be offline.');
    }
});

window.addEventListener('beforeunload', (event) => {
    if (isRequestInProgress) {
        event.preventDefault();
        event.returnValue = 'A calculation is still running. Do you want to leave and stop it?';

        if (currentAbortController) {
            currentAbortController.abort();
        }

        const resetData = new FormData();
        resetData.append('action', 'ping');
        resetData.append('forceReset', 'true');
        navigator.sendBeacon(CALCULATE_URL, resetData);

        return event.returnValue;
    }
});