// ─── Context path ─────────────────────────────────────────────────────────────
const CTX = '/CalculatorApp';
const EVALUATE_URL  = CTX + '/evaluate';
const CALCULATE_URL = CTX + '/calculate';
const HISTORY_URL   = CTX + '/history';

// ─── DOM refs ─────────────────────────────────────────────────────────────────
const output = document.getElementById('output-screen');
let currentAbortController = null;

// ─── History State & Cache ────────────────────────────────────────────────────
let historyCache     = null;
let isHistoryDirty   = true;
let isHistoryLoading = false;

var _fullResultCache = {};

var _lastTapTime = 0;
output.addEventListener('input', function(event) {
    // Only run this on mobile
    if (!isMobile()) return;

    const val = this.value;
    const lastChar = val.slice(-1).toLowerCase();

    const shortcuts = {
        'f': calculateFactorial,
        'g': calculateGCD,
        'l': calculateLCM,
        'm': calculateMod,
        'p': checkPrime,
        'h': displayHistory
    };

    if (shortcuts[lastChar]) {
        this.value = val.slice(0, -1);
        
        shortcuts[lastChar]();
    }
});

// ─── Request lock ─────────────────────────────────────────────────────────────
let isRequestInProgress = false;

function setRequestLock(v) { isRequestInProgress = v; }
function isRequestLocked() { return isRequestInProgress; }

function isMobile() {
    return window.matchMedia('(max-width: 600px)').matches;
}

function isDesktop() {
    return window.matchMedia('(min-width: 601px)').matches;
}

window.addEventListener('resize', function() {
    var historyBar = document.getElementById('history-bar');
    if (historyBar) {
        historyBar.style.removeProperty('top');
        historyBar.style.removeProperty('left');
        historyBar.style.removeProperty('position');
    }
});

// ─── Loading overlay ──────────────────────────────────────────────────────────
const overlay = document.createElement('div');
overlay.className = 'loading-overlay';
overlay.innerHTML = '<div class="spinner"></div>';
document.body.appendChild(overlay);

function setLoading(on) { overlay.classList.toggle('active', on); }

if (navigator.maxTouchPoints > 0) {
    document.body.classList.add('dark-mode');
    var modeBtn = document.querySelector('.toggle-mode');
    if (modeBtn) modeBtn.textContent = 'Light Mode';

    var notesBtn = document.querySelector('.show-notes-button');
    if (notesBtn) notesBtn.style.display = 'none';
}

// ─── Generic POST ─────────────────────────────────────────────────────────────
async function postForm(url, params) {
    currentAbortController = new AbortController();
    const res = await fetch(url, {
        method: 'POST',
        body: new URLSearchParams(params),
        signal: currentAbortController.signal
    });
    const text = await res.text();
    try { return JSON.parse(text); }
    catch (_) { return { success: false, error: 'Server returned invalid response (HTTP ' + res.status + ').' }; }
}

// ─── GET with error surfacing ─────────────────────────────────────────────────
async function getJson(url) {
    const res  = await fetch(url, { headers: { 'X-Calculator-Client': 'true' } });
    const text = await res.text();
    try { return { ok: res.ok, data: JSON.parse(text) }; }
    catch (_) { return { ok: false, data: { success: false, error: 'Server returned invalid response (HTTP ' + res.status + ').' } }; }
}

// ─── Kill backend computation ─────────────────────────────────────────────────
function killBackendComputation() {
    if (currentAbortController) {
        currentAbortController.abort();
        currentAbortController = null;
    }
    var resetUrl = '?action=ping&forceReset=true';
    navigator.sendBeacon(CALCULATE_URL + resetUrl, new Blob([]));
    navigator.sendBeacon(EVALUATE_URL  + resetUrl, new Blob([]));
}

// ─── Busy-guard confirm dialog ────────────────────────────────────────────────
function showBusyConfirm(actionLabel) {
    return new Promise(function(resolve) {
        var existing = document.getElementById('busy-confirm-backdrop');
        if (existing) existing.remove();

        var backdrop = document.createElement('div');
        backdrop.id = 'busy-confirm-backdrop';
        backdrop.className = 'modal-backdrop';

        var panel = document.createElement('div');
        panel.className = 'modal-panel';
        panel.style.cssText = 'max-width:380px;';

        panel.innerHTML =
            '<div class="modal-header">' +
                '<span class="modal-title" style="color:#c0392b;">⚠️ Calculation Running</span>' +
            '</div>' +
            '<div style="font-size:14px;line-height:1.7;padding:4px 0;">' +
                'A calculation is currently running on the server.<br>' +
                '<strong>' + actionLabel + '</strong> cannot start until it finishes.<br><br>' +
                'Choose an option:' +
            '</div>' +
            '<div class="modal-actions">' +
                '<button class="modal-btn modal-cancel" id="busy-wait-btn">⏳ Keep Waiting</button>' +
                '<button class="modal-btn modal-confirm" id="busy-stop-btn" ' +
                    'style="background:#c0392b;">🛑 Stop & Proceed</button>' +
            '</div>';

        backdrop.appendChild(panel);
        document.body.appendChild(backdrop);

        function close(result) { backdrop.remove(); resolve(result); }

        document.getElementById('busy-wait-btn').addEventListener('click', function() { close(false); });
        document.getElementById('busy-stop-btn').addEventListener('click', function() { close(true); });
        backdrop.addEventListener('click', function(e) { if (e.target === backdrop) close(false); });
    });
}

async function ensureNotBusy(actionLabel) {
    if (!isRequestLocked()) return true;

    var stop = await showBusyConfirm(actionLabel);
    if (stop) {
        killBackendComputation();
        setRequestLock(false);
        setLoading(false);
        return true;
    }
    return false;
}

// ─── Input Modal ──────────────────────────────────────────────────────────────
function showInputModal(title, placeholder, prefill) {
    if (prefill === undefined) prefill = '';
    return new Promise(function(resolve) {
        var backdrop = document.createElement('div');
        backdrop.className = 'modal-backdrop';
        var panel = document.createElement('div');
        panel.className = 'modal-panel';
        panel.innerHTML =
            '<div class="modal-header">' +
                '<span class="modal-title">' + title + '</span>' +
                '<button class="modal-close" title="Cancel">&#x2715;</button>' +
            '</div>' +
            '<textarea class="modal-input" placeholder="' + placeholder + '" ' +
                'spellcheck="false" autocomplete="off" autocorrect="off" autocapitalize="off">' +
            prefill + '</textarea>' +
            '<div class="modal-error" style="display:none"></div>' +
            '<div class="modal-actions">' +
                '<button class="modal-btn modal-cancel">Cancel</button>' +
                '<button class="modal-btn modal-confirm">OK</button>' +
            '</div>';

        backdrop.appendChild(panel);
        document.body.appendChild(backdrop);

        var textarea   = panel.querySelector('.modal-input');
        var errorBox   = panel.querySelector('.modal-error');
        var confirmBtn = panel.querySelector('.modal-confirm');
        var cancelBtn  = panel.querySelector('.modal-cancel');
        var closeBtn   = panel.querySelector('.modal-close');

        setTimeout(function() {
            textarea.focus();
            textarea.setSelectionRange(textarea.value.length, textarea.value.length);
        }, 50);

        function close(value) { backdrop.remove(); resolve(value); }

        confirmBtn.addEventListener('click', function() { close(textarea.value); });
        cancelBtn.addEventListener('click',  function() { close(null); });
        closeBtn.addEventListener('click',   function() { close(null); });
        backdrop.addEventListener('click',   function(e) { if (e.target === backdrop) close(null); });
        textarea.addEventListener('keydown', function(e) {
            if (e.key === 'Enter' && e.ctrlKey) { e.preventDefault(); close(textarea.value); }
            if (e.key === 'Escape')              { e.preventDefault(); close(null); }
        });
        backdrop._showError = function(msg) {
            errorBox.textContent = msg;
            errorBox.style.display = msg ? 'block' : 'none';
            textarea.focus();
        };
    });
}

async function showTwoInputModal(t1, p1, t2, p2) {
    var v1 = await showInputModal(t1, p1);
    if (v1 === null) return null;
    var v2 = await showInputModal(t2, p2);
    if (v2 === null) return null;
    return [v1.trim(), v2.trim()];
}

function showNotice(message, type) {
    type = type || 'error';
    document.querySelectorAll('.calc-notice').forEach(function(el) { el.remove(); });
    var notice = document.createElement('div');
    notice.className = 'calc-notice calc-notice--' + type;
    notice.textContent = message;
    notice.title = 'Click to dismiss';
    notice.style.cursor = 'pointer';
    notice.addEventListener('click', function() { notice.remove(); });
    var calc = document.querySelector('.calculator');
    if (calc) calc.parentNode.insertBefore(notice, calc);
    setTimeout(function() { notice.remove(); }, 10000);
}

function clearNotice() {
    document.querySelectorAll('.calc-notice').forEach(function(el) { el.remove(); });
}

function mapError(err) {
    if (!err) return 'An unexpected error occurred.';
    var e = String(err).trim();
    if (e.toLowerCase().includes('timeout') || e.toLowerCase().includes('timed out') || e === 'Timeout') {
        return 'Calculation timed out (2-minute limit reached). Your input is too large — please reduce it and try again.';
    }
    if (e === 'Busy') {
        return 'Server is busy with another request. Please wait a moment.';
    }
    if (e.toLowerCase().includes('low on memory') || e.toLowerCase().includes('server is low')) {
        return 'Server is low on memory right now. Please try a smaller input or wait a moment.';
    }
    if (e.toLowerCase().includes('neither a decimal digit') ||
        e.toLowerCase().includes('notation exponential mark') ||
        e.toLowerCase().includes('invalid character') ||
        e.toLowerCase().includes('invalid expression')) {
        setRequestLock(false);
        setLoading(false);
        return 'Invalid character in expression — only digits, operators, parentheses, and "e" notation are allowed.';
    }
    return e;
}

// ─── Expression validator — allow digits, operators, parens, dot, and 'e'/'E' only ──
function validateExpression(expr) {
    // Allow: 0-9, +, -, *, /, %, ., (, ), ^, space, and 'e'/'E' for scientific notation
    var invalidChar = expr.match(/[^0-9+\-*/%.()\^eE\s]/);
    if (invalidChar) {
        return 'Invalid character "' + invalidChar[0] + '" — only digits, operators, parentheses, and "e" notation are allowed.';
    }
    if (/[eE]/.test(expr)) {
        if (!/\d[eE][+\-]?\d/.test(expr)) {
            return 'Invalid "e" notation — use format like 1e9 or 1.5e+10.';
        }
    }
    return null; 
}

// ─── Calculator Operations ────────────────────────────────────────────────────

async function Calculate() {
    var expression = output.value.trim();
    if (!expression) return;

    if (!(await ensureNotBusy('Calculate (=)'))) return;

    var hasOperator = /[+\-*/%]/.test(expression) || expression.includes('**');
    if (!hasOperator) { clearNotice(); output.scrollLeft = output.scrollWidth; return; }

    if (historyCache) {
        var cached = historyCache.find(function(e) { return e.expression === expression; });
        if (cached) {
            output.value = cached.result;
            output.scrollLeft = output.scrollWidth;
            return;
        }
    }

    var validationError = validateExpression(expression);
    if (validationError) {
        showNotice(validationError);
        return;
    }

    clearNotice();
    setRequestLock(true);
    setLoading(true);

    var pendingExpression = expression;
    var pendingResult     = null;
    var pendingDigitLen   = 0;

    try {
        var data = await postForm(EVALUATE_URL, { expression: expression });
        if (data.success) {
            output.value = data.result;
            output.scrollLeft = output.scrollWidth;
            _fullResultCache[expression] = data.result;
            pendingResult   = data.result;
            pendingDigitLen = data.digitLength;
        } else {
            setLoading(false);
            setRequestLock(false);
            showNotice(mapError(data.error));        }
    } catch (e) {
        if (e.name !== 'AbortError') showNotice('Network error — please check your connection and try again.');
    } finally {
        setLoading(false);
        setRequestLock(false);
    }

    if (pendingResult !== null) {
        saveHistory(pendingExpression, pendingResult, pendingDigitLen);
    }
}

async function calculateMod() {
    if (!(await ensureNotBusy('Mod'))) return;

    setRequestLock(true);

    var pendingResult   = null;
    var pendingDigitLen = 0;
    var num1Str, num2Str;

    try {
        var values = await showTwoInputModal(
            'Remainder — Enter first value',  'e.g. 10',
            'Remainder — Enter second value', 'e.g. 7'
        );
        if (!values) return;
        num1Str = values[0];
        num2Str = values[1];

        setLoading(true);

        var data = await postForm(CALCULATE_URL, { action: 'mod', num1: num1Str, num2: num2Str });
        if (data.success) {
            output.value = data.result;
            output.disabled = false;
            _fullResultCache[num1Str + ' mod ' + num2Str] = data.result;
            pendingResult   = data.result;
            pendingDigitLen = data.digitLength;
        } else {
            showNotice(mapError(data.error));
        }
    } catch (e) {
        if (e.name !== 'AbortError') showNotice('Network error — please try again.');
    } finally {
        setLoading(false);
        setRequestLock(false);
    }

    if (pendingResult !== null) {
        saveHistory(num1Str + ' mod ' + num2Str, pendingResult, pendingDigitLen);
    }
}

async function calculateGCD() {
    if (!(await ensureNotBusy('GCD'))) return;

    setRequestLock(true);

    var pendingResult   = null;
    var pendingDigitLen = 0;
    var num1Str, num2Str;

    try {
        var values = await showTwoInputModal(
            'GCD (Greatest Common Divisor) — Enter first value', 'e.g. 48',
            'GCD — Enter second value', 'e.g. 18'
        );
        if (!values) return;
        num1Str = values[0];
        num2Str = values[1];

        setLoading(true);

        var data = await postForm(CALCULATE_URL, { action: 'gcd', num1: num1Str, num2: num2Str });
        if (data.success) {
            output.value = data.result;
            output.disabled = false;
            _fullResultCache['GCD(' + num1Str + ', ' + num2Str + ')'] = data.result;
            pendingResult   = data.result;
            pendingDigitLen = data.digitLength;
        } else {
            showNotice(mapError(data.error));
        }
    } catch (e) {
        if (e.name !== 'AbortError') showNotice('Network error — please try again.');
    } finally {
        setLoading(false);
        setRequestLock(false);
    }

    if (pendingResult !== null) {
        saveHistory('GCD(' + num1Str + ', ' + num2Str + ')', pendingResult, pendingDigitLen);
    }
}

async function calculateLCM() {
    if (!(await ensureNotBusy('LCM'))) return;

    setRequestLock(true);

    var pendingResult   = null;
    var pendingDigitLen = 0;
    var num1Str, num2Str;

    try {
        var values = await showTwoInputModal(
            'LCM (Least Common Multiple) — Enter first value', 'e.g. 12',
            'LCM — Enter second value', 'e.g. 18'
        );
        if (!values) return;
        num1Str = values[0];
        num2Str = values[1];

        setLoading(true);

        var data = await postForm(CALCULATE_URL, { action: 'lcm', num1: num1Str, num2: num2Str });
        if (data.success) {
            output.value = data.result;
            output.disabled = false;
            _fullResultCache['LCM(' + num1Str + ', ' + num2Str + ')'] = data.result;
            pendingResult   = data.result;
            pendingDigitLen = data.digitLength;
        } else {
            showNotice(mapError(data.error));
        }
    } catch (e) {
        if (e.name !== 'AbortError') showNotice('Network error — please try agairialn.');
    } finally {
        setLoading(false);
        setRequestLock(false);
    }

    if (pendingResult !== null) {
        saveHistory('LCM(' + num1Str + ', ' + num2Str + ')', pendingResult, pendingDigitLen);
    }
}

async function calculateFactorial() {
    var val = output.value.trim();
    if (!val) return;
    if (!(await ensureNotBusy('Factorial (!)'))) return;

    setRequestLock(true);
    setLoading(true);

    var pendingResult   = null;
    var pendingDigitLen = 0;

    try {
        var data = await postForm(CALCULATE_URL, { action: 'factorial', num1: val });
        if (data.success) {
            output.value = data.result;
            output.disabled = false;
            _fullResultCache[val + '!'] = data.result;
            pendingResult   = data.result;
            pendingDigitLen = data.digitLength;
        } else {
            showNotice(mapError(data.error));
        }
    } catch (e) {
        if (e.name !== 'AbortError') showNotice('Network error — please try again.');
    } finally {
        setLoading(false);
        setRequestLock(false);
    }

    if (pendingResult !== null) {
        saveHistory(val + '!', pendingResult, pendingDigitLen);
    }
}

async function checkPrime() {
    if (!(await ensureNotBusy('Prime Check'))) return;

    setRequestLock(true);

    var pendingVerdict = null;
    var cleaned;

    try {
        var userInput = await showInputModal('Check Prime Number', 'Enter a number');
        if (userInput === null) return;
        cleaned = userInput.replace(/,/g, '').trim();
        if (!cleaned) return;

        setLoading(true);

        var data = await postForm(CALCULATE_URL, { action: 'prime', num1: cleaned });
        if (data.success) {
            showNotice(data.result, data.result.startsWith('A') ? 'success' : 'info');
            _fullResultCache['checkPrime{' + cleaned + '}'] = cleaned + '\n' + data.result;
            pendingVerdict = data.result;
        } else {
            showNotice(mapError(data.error));
        }
    } catch (e) {
        if (e.name !== 'AbortError') showNotice('Network error — please try again.');
    } finally {
        setLoading(false);
        setRequestLock(false);
    }

    if (pendingVerdict !== null) {
        saveHistory('checkPrime{' + cleaned + '}', cleaned, cleaned.length, pendingVerdict);
    }
}

function display(input) {
    clearNotice();
    var start = output.selectionStart;
    var end   = output.selectionEnd;
    var val   = output.value;
    output.value = val.slice(0, start) + input + val.slice(end);
    var newPos = start + input.length;
    output.setSelectionRange(newPos, newPos);
    output.focus();
    output.scrollLeft = output.scrollWidth;
}

function Delete() {
    clearNotice();
    if (output.value.length > 0) {
        var start = output.selectionStart;
        var end   = output.selectionEnd;

        if (start === end) {
            if (start === 0) return;
            var newVal = output.value.slice(0, start - 1) + output.value.slice(end);
            output.value = newVal;
            var newPos = start - 1;
            output.setSelectionRange(newPos, newPos);
        } else {
            var newVal = output.value.slice(0, start) + output.value.slice(end);
            output.value = newVal;
            output.setSelectionRange(start, start);
        }
    }
    output.focus();
}

function Clear() {
    clearNotice();
    output.value = '';
    output.disabled = false;
    output.focus();
}

async function reloadPage() {
    if (isRequestLocked()) {
        var stop = await showBusyConfirm('Reload Page');
        if (!stop) return;
        killBackendComputation();
        setRequestLock(false);
        setLoading(false);
    }
    setTimeout(function() { window.location.reload(); }, 80);
}

function toggleNotes() {
    var el = document.getElementById('notes');
    el.style.display = (el.style.display === 'none' || el.style.display === '') ? 'block' : 'none';
}

function toggleDarkMode() {
    document.body.classList.toggle('dark-mode');
    var btn = document.querySelector('.toggle-mode.dark-mode');
    if (btn) btn.textContent = document.body.classList.contains('dark-mode') ? 'Light Mode' : 'Dark Mode';
}

// ─── History Logic ────────────────────────────────────────────────────────────

async function saveHistory(expression, result, digitLength, verdict) {
    var digitsLength = (digitLength > 0) ? (digitLength + ' digits') : '';
    if (verdict) {
        digitsLength = digitsLength + '|' + verdict;
    }
    try {
        var payload = { expression: expression, result: result, digitsLength: digitsLength };

        var res = await fetch(HISTORY_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Calculator-Client': 'true' },
            body: JSON.stringify(payload)
        });
        if (!res.ok) { console.error('History save failed:', res.status); return; }
        var data = await res.json();
        if (data.success) {
            isHistoryDirty = true;
        } else {
            console.error('History save failed:', data.error);
        }
    } catch (e) {
        console.error('History sync error:', e);
    }
}

async function displayHistory() {
    if (!(await ensureNotBusy('Open History'))) return;

    var historyBar = document.getElementById('history-bar');
    historyBar.classList.toggle('visible');
    if (!historyBar.classList.contains('visible')) return;

    if (!isHistoryDirty && historyCache) { renderHistory(historyCache); return; }
    if (isHistoryLoading) return;

    isHistoryLoading = true;
    setLoading(true);

    try {
        var r = await getJson(HISTORY_URL);
        if (r.ok && r.data.success) {
            historyCache   = r.data.history || [];
            isHistoryDirty = false;
            renderHistory(historyCache);
        } else {
            showNotice(mapError((r.data && r.data.error) || 'Failed to load history'), 'error');
        }
    } catch (e) {
        showNotice('Network error loading history.', 'error');
    } finally {
        isHistoryLoading = false;
        setLoading(false);
    }
}

function renderHistory(historyArray) {
    var historyBar = document.getElementById('history-bar');
    historyBar.innerHTML = '';

    var headTail = isMobile() ? 100 : 150;

    var scrollWrapper = document.createElement('div');
    scrollWrapper.className = 'history-scroll-wrapper';

    if (historyArray && historyArray.length > 0) {
        historyArray.forEach(function(entry) {
            var itemDiv = document.createElement('div');
            itemDiv.className = 'history-item';

            var isPrimeEntry = entry.expression && entry.expression.indexOf('checkPrime{') === 0;

            var contentSpan = document.createElement('span');
            contentSpan.className = 'history-content';

            var totalDigits = 0;
            if (entry.digitsLength) {
                var dm = entry.digitsLength.match(/(\d+)/);
                if (dm) totalDigits = parseInt(dm[1], 10);
            }

            if (isPrimeEntry) {
                var verdict = '';
                var digitsDisplay = entry.digitsLength || '';
                var pipeIdx = digitsDisplay.indexOf('|');
                if (pipeIdx !== -1) {
                    verdict       = digitsDisplay.substring(pipeIdx + 1);
                    digitsDisplay = digitsDisplay.substring(0, pipeIdx);
                }
                var digitLine   = digitsDisplay ? '\n[' + digitsDisplay + ']' : '';
                var verdictLine = verdict ? '\n' + verdict : '';

                var inputDisplay;
                if (totalDigits <= 25) {
                    var primeNum = extractPrimeNumber(entry.expression) || entry.result;
                    inputDisplay = 'checkPrime{' + primeNum + '}';
                } else {
                    inputDisplay = 'checkPrime{...} → use 📋 to copy full input';
                }
                contentSpan.textContent = inputDisplay + verdictLine + digitLine;
            } else {
                var digitLine   = entry.digitsLength ? '\n[' + entry.digitsLength + ']' : '';
                var truncResult = truncateText(entry.result, headTail, headTail, totalDigits);
                contentSpan.textContent = entry.expression + ' =\n' + truncResult + digitLine;
            }

            var btnCol = document.createElement('div');
            btnCol.className = 'history-btn-col';

            var copyBtn = document.createElement('button');
            copyBtn.className = 'copy-history-btn';
            copyBtn.innerHTML = '&#128203;';
            copyBtn.title = 'Copy full result';
            (function(e, isPrime, btn) {
                btn.onclick = function(ev) {
                    ev.stopPropagation();
                    ev.preventDefault();

                    var cacheKey = isPrime
                        ? ('checkPrime{' + (extractPrimeNumber(e.expression) || '') + '}')
                        : e.expression;
                    var copyText;
                    if (isPrime) {
                        var cached = _fullResultCache[cacheKey];
                        if (cached) {
                            copyText = cached;
                        } else {
                            var storedNum    = extractPrimeNumber(e.expression) || e.result;
                            var storedVerdict = '';
                            var pi = (e.digitsLength || '').indexOf('|');
                            if (pi !== -1) storedVerdict = e.digitsLength.substring(pi + 1);
                            copyText = storedVerdict ? (storedNum + '\n' + storedVerdict) : storedNum;
                        }
                    } else {
                        var isTouchDevice = navigator.maxTouchPoints > 0;
                        var cached = _fullResultCache[cacheKey];

                        if (!isTouchDevice && cached) {
                            copyText = cached;
                        } else {
                            copyText = e.result;
                        }
                    }

                    if (!copyText) { showNotice('Nothing to copy.', 'error'); return; }


                    function onSuccess() {
                        var prev = btn.innerHTML;
                        btn.innerHTML = '&#9989;';
                        setTimeout(function() { btn.innerHTML = prev; }, 1500);
                    }

                    function onFail() {
                        showNotice('Copy failed — please long-press and copy manually.', 'error');
                    }

                    function execCopy() {
                        var ta = document.createElement('textarea');
                        ta.value = copyText;
                        ta.style.cssText = 'position:fixed;top:0;left:0;opacity:0;width:1px;height:1px;';
                        document.body.appendChild(ta);
                        ta.focus(); ta.select();
                        try { document.execCommand('copy') ? onSuccess() : onFail(); }
                        catch (_) { onFail(); }
                        document.body.removeChild(ta);
                    }

                    if (navigator.clipboard && navigator.clipboard.writeText) {
                        navigator.clipboard.writeText(copyText).then(onSuccess).catch(execCopy);
                    } else {
                        execCopy();
                    }
                };
            })(entry, isPrimeEntry, copyBtn);

            btnCol.appendChild(copyBtn);
            itemDiv.appendChild(contentSpan);
            itemDiv.appendChild(btnCol);
            scrollWrapper.appendChild(itemDiv);
        });
    } else {
        var empty = document.createElement('div');
        empty.className = 'history-item';
        empty.textContent = 'No history yet.';
        scrollWrapper.appendChild(empty);
    }

    historyBar.appendChild(scrollWrapper);

    if (historyArray && historyArray.length > 0) {
        var clearBtn = document.createElement('button');
        clearBtn.className = 'clear-history-btn';
        clearBtn.textContent = 'Clear History';
        clearBtn.onclick = clearHistory;
        historyBar.appendChild(clearBtn);
    }

    scrollWrapper.scrollTop = scrollWrapper.scrollHeight;
}

function truncateText(str, headLen, tailLen, totalDigits) {
    if (!str) return '';
    var threshold = headLen + tailLen;
    if (str.length <= threshold) return str;
    var total  = (totalDigits && totalDigits > str.length) ? totalDigits : str.length;
    var hidden = total - headLen - tailLen;

    var middleMsg = isMobile()
        ? '\n  ... ' + hidden.toLocaleString() + ' digits hidden — use desktop to copy full result ...\n'
        : '\n  ... ' + hidden.toLocaleString() + ' digits hidden ...\n';

    return (
        str.substring(0, headLen) +
        middleMsg +
        str.substring(str.length - tailLen)
    );
}

function extractPrimeNumber(expression) {
    var m = expression.match(/^checkPrime\{([\s\S]*)\}$/);
    return m ? m[1] : null;
}

async function clearHistory() {
    if (!historyCache || historyCache.length === 0) {
        showNotice('History is already empty.', 'info');
        return;
    }
    try {
        var res = await fetch(HISTORY_URL + '?action=clear', {
            method: 'POST',
            headers: { 'X-Calculator-Client': 'true' }
        }).then(function(r) { return r.json(); });
        if (res.success) {
            historyCache   = [];
            isHistoryDirty = false;
            renderHistory([]);
            showNotice('History cleared.', 'success');
        } else {
            showNotice(mapError(res.error) || 'Failed to clear history', 'error');
        }
    } catch (e) {
        showNotice('Error clearing history.', 'error');
    }
}

// ─── Keyboard handler ─────────────────────────────────────────────────────────
document.addEventListener('keydown', function(event) {
    if (event.ctrlKey && event.key === 'r') return;
    if (document.querySelector('.modal-backdrop')) return;
    if (output.disabled && !event.ctrlKey) { event.preventDefault(); return; }

    var focusedOnOutput = (document.activeElement === output);

    if (event.key === 'Enter') {
        event.preventDefault(); Calculate();
    } else if (event.key === 'Backspace' && event.ctrlKey) {
        event.preventDefault(); Clear();
    } else if (event.key === 'Backspace' && !focusedOnOutput) {
        event.preventDefault(); Delete();
    } else if ((event.key >= '0' && event.key <= '9') || ['+', '-', '*', '/', '%'].includes(event.key)) {
        if (!focusedOnOutput) { event.preventDefault(); display(event.key); }
    } else if (!focusedOnOutput || isMobile() || isDesktop()) {
        var k = event.key;
        if (k.toLowerCase() === 'f') {
            event.preventDefault(); calculateFactorial();
        } else if (k.toLowerCase() === 'g') {
            event.preventDefault(); calculateGCD();
        } else if (k.toLowerCase() === 'l') {
            event.preventDefault(); calculateLCM();
        } else if (k.toLowerCase() === 'm') {
            event.preventDefault(); calculateMod();
        } else if (k.toLowerCase() === 'p') {
            event.preventDefault(); checkPrime();
        } else if (k.toLowerCase() === 'h') {
            event.preventDefault(); displayHistory();
        }
    }
});

// ─── Global Offset Variable ──────────────────────────────────────────────────
let timeOffset = 0; // Difference between system time and VPN time

async function syncWithVPN() {
    try {
        // Fetch time based on your current (VPN) IP
        const response = await fetch('https://worldtimeapi.org/api/ip');
        const data = await response.json();
        
        // Calculate the difference between the API time and your local system time
        const vpnTime = new Date(data.datetime);
        const systemTime = new Date();
        timeOffset = vpnTime.getTime() - systemTime.getTime();
        
        console.log("Time synced with VPN location:", data.timezone);
    } catch (err) {
        console.error("Could not sync time, defaulting to system time.", err);
    }
}

// Sync immediately on load, and maybe every 5 minutes in case VPN toggles
syncWithVPN();
setInterval(syncWithVPN, 300000); 

function clock() {
    // Get current system time and add the offset
    const now = new Date(new Date().getTime() + timeOffset);

    const hr24 = now.getHours();
    const hr12 = hr24 % 12 || 12;
    
    document.getElementById('ampm').innerHTML   = hr24 >= 12 ? 'PM' : 'AM';
    document.getElementById('hrs').innerHTML    = addZero(hr12);
    document.getElementById('min').innerHTML    = addZero(now.getMinutes());
    document.getElementById('sec').innerHTML    = addZero(now.getSeconds());
    
    document.getElementById('day').innerHTML    = now.toLocaleDateString('en-US', { weekday: 'long' });
    document.getElementById('month').innerHTML  = now.toLocaleDateString('en-US', { month: 'long' });
    document.getElementById('date').innerHTML   = now.getDate();
}

function addZero(n) { return n < 10 ? '0' + n : n; }
setInterval(clock, 1000);

// ─── Page load — reset any stale backend lock ─────────────────────────────────
window.addEventListener('load', async function() {
    try {
        var resetParams = new URLSearchParams({ action: 'ping', forceReset: 'true' });
        await fetch(CALCULATE_URL, { method: 'POST', body: resetParams });
        await fetch(EVALUATE_URL,  { method: 'POST', body: resetParams });
        console.log('Session locks reset on load.');
    } catch (e) {
        console.warn('Initial server sync failed. Server might be offline.');
    }
    setLoading(false);
});

// ─── beforeunload / pagehide ──────────────────────────────────────────────────
window.addEventListener('beforeunload', function(event) {
    if (!isRequestInProgress) return;
    event.preventDefault();
    event.returnValue = 'A calculation is still running. Leaving now will stop it on the server.';
});

window.addEventListener('pagehide', function() {
    if (!isRequestInProgress) return;
    var resetUrl = '?action=ping&forceReset=true';
    navigator.sendBeacon(CALCULATE_URL + resetUrl, new Blob([]));
    navigator.sendBeacon(EVALUATE_URL  + resetUrl, new Blob([]));
});

// ─── Feedback Modal ───────────────────────────────────────────────────────────
function toggleFeedback() {
    var existing = document.getElementById('feedback-backdrop');
    
    if (existing) {
        existing.closeModal(); 
        return;
    }

    var backdrop = document.createElement('div');
    backdrop.id = 'feedback-backdrop';
    backdrop.className = 'modal-backdrop';

    var panel = document.createElement('div');
    panel.className = 'modal-panel';
    panel.style.cssText = 'max-width:400px;';

    panel.innerHTML =
        '<div class="modal-header">' +
            '<span class="modal-title"> 💬 Feedback / Issues</span>' +
            '<button class="modal-close" title="Close">&#x2715;</button>' +
        '</div>' +
        '<div style="font-size:14px;line-height:1.8;color:var(--text-primary);">' +
            'Found a bug or have a suggestion?<br>' +
            'Feel free to reach out via the link below! Your feedback is invaluable in improving this calculator.' +
        '</div>' +
        '<div class="modal-actions" style="justify-content:center;gap:12px;flex-wrap:wrap;">' +
            '<a href="https://srikanthr.in/#contact" target="_blank" rel="noopener" ' +
                'style="display:inline-flex;align-items:center;gap:6px;' +
                'padding:10px 22px;border-radius:10px;background:#1976d2;' +
                'color:#fff;font-size:14px;font-weight:600;text-decoration:none;' +
                'transition:background 0.15s;"' +
                'onmouseover="this.style.background=\'#1565c0\'"' +
                'onmouseout="this.style.background=\'#1976d2\'">' +
                '🌐 Contact Developer' +
            '</a>' +
        '</div>';

    backdrop.appendChild(panel);
    document.body.appendChild(backdrop);

    const handleEsc = (e) => {
        if (e.key === 'Escape') closeModal();
    };

    const closeModal = () => {
        document.removeEventListener('keydown', handleEsc);
        backdrop.remove();
    };
    
    backdrop.closeModal = closeModal;

    document.addEventListener('keydown', handleEsc);

    panel.querySelector('.modal-close').addEventListener('click', closeModal);

    backdrop.addEventListener('click', function(e) {
        if (e.target === backdrop) closeModal();
    });
}