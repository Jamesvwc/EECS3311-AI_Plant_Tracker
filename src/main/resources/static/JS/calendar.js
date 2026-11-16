// F02 calendar view, F03 availability updates (+ undo), F04 watering check-in.

const { el, textEl, api } = PO;

let shownMonth = new Date();
shownMonth.setDate(1);

// ---------- F04 overview ----------

async function loadOverview() {
    const box = el('overview');
    try {
        const o = await api('/api/calendar/overview');
        box.replaceChildren(
            column('⚠️ Missed', o.overdue, true, 'Nothing missed. Nice!'),
            column('\u{1F4A7} Due now', o.dueNow, true, 'Nothing due right now.'),
            column('\u{1F4C5} Coming up (7 days)', o.upcoming, false, 'Nothing planned this week.'));
    } catch (err) {
        box.replaceChildren(textEl('p', err.message, 'error-text'));
    }
}

function column(title, items, canWater, empty) {
    const col = textEl('div', '', 'overview-col');
    col.appendChild(textEl('h4', title));
    const ul = document.createElement('ul');
    if (!items.length) ul.appendChild(textEl('li', empty, 'muted'));
    items.forEach((i) => {
        const li = document.createElement('li');
        const label = textEl('span', '');
        label.appendChild(textEl('strong', i.plantName));
        label.appendChild(document.createTextNode(' ' + PO.formatDateTime(i.dueAt) + (i.location ? ' · ' + i.location : '')));
        li.appendChild(label);
        if (canWater) {
            li.appendChild(PO.button('Watered', 'mini-btn', async (e) => {
                const r = await PO.busy(e.target, () => api(`/api/plants/${i.plantId}/water`, { method: 'POST' }));
                if (r) {
                    PO.toast(r.message);
                    refreshAll();
                }
            }));
        }
        ul.appendChild(li);
    });
    col.appendChild(ul);
    return col;
}

// ---------- F03 availability ----------

async function loadAvailability() {
    try {
        const { availability, lastChange } = await api('/api/calendar/availability');
        el('currentAvailability').textContent = 'Current: ' + (availability.summary || 'not set')
            + (lastChange ? ` · Last change: ${lastChange}` : '');
    } catch (err) {
        el('currentAvailability').textContent = '';
    }
}

el('availabilityForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const text = el('availabilityText').value.trim();
    const file = el('availabilityFile').files[0];
    if (!text && !file) {
        PO.toast('Type when you are free, or choose a file.', 'error');
        return;
    }
    const form = new FormData();
    if (file) form.append('file', file, file.name); else form.append('text', text);
    const result = el('availabilityResult');
    const waiting = textEl('div', '', 'status');
    waiting.append(textEl('span', '', 'spinner'), textEl('span', file ? 'Reading your file...' : 'The agent is reading your availability...'));
    result.replaceChildren(waiting);
    PO.busy(el('availabilityBtn'), async () => {
        try {
            const r = await api('/api/calendar/availability', { method: 'POST', body: form });
            const box = textEl('div', '', 'explanation');
            PO.renderRichText(box, r.explanation);
            result.replaceChildren(box);
            if (r.examples.length) {
                const ul = document.createElement('ul');
                r.examples.forEach((x) => ul.appendChild(textEl('li', x)));
                result.appendChild(ul);
            }
            el('availabilityText').value = '';
            el('availabilityFile').value = '';
            refreshAll();
        } catch (err) {
            // 422 = the agent couldn't understand it and asks for clarification (UC04 alternative)
            result.replaceChildren(textEl('p', (err.status === 422 ? '\u{1F914} ' : '') + err.message,
                err.status === 422 ? 'notice' : 'error-box'));
        }
    });
});

el('undoBtn').addEventListener('click', (e) => PO.busy(e.target, async () => {
    const r = await api('/api/calendar/undo', { method: 'POST' });
    PO.toast(r.message);
    el('availabilityResult').replaceChildren();
    refreshAll();
}));

// ---------- F02 month view ----------

async function loadMonth() {
    const first = new Date(shownMonth);
    const start = new Date(first);
    start.setDate(1 - ((first.getDay() + 6) % 7)); // Monday on or before the 1st
    const end = new Date(start);
    end.setDate(start.getDate() + 41);
    el('monthTitle').textContent = first.toLocaleDateString(undefined, { month: 'long', year: 'numeric' });

    let items = [];
    try {
        items = await api(`/api/calendar?from=${iso(start)}&to=${iso(end)}`);
    } catch (err) {
        PO.toast(err.message, 'error');
    }
    const byDay = {};
    items.forEach((i) => (byDay[i.dueAt.slice(0, 10)] = byDay[i.dueAt.slice(0, 10)] || []).push(i));

    const grid = el('month');
    grid.replaceChildren(...['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => textEl('div', d, 'day-name')));
    const today = iso(new Date());
    const now = new Date();
    for (let d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
        const key = iso(d);
        const cell = textEl('div', '', 'day' + (d.getMonth() !== first.getMonth() ? ' other-month' : '') + (key === today ? ' today' : ''));
        cell.appendChild(textEl('div', String(d.getDate()), 'day-number'));
        (byDay[key] || []).forEach((i) => {
            const missed = i.status === 'PLANNED' && new Date(i.dueAt) < now;
            const cls = 'entry' + (i.status === 'DONE' ? ' done' : missed ? ' missed' : i.grouped ? ' grouped' : '');
            const chip = textEl('a', `${i.dueAt.slice(11, 16)} ${i.plantName}`, cls);
            chip.href = `currentlistofplants.html?plant=${i.plantId}`;
            chip.title = `${i.plantName}: ${PO.formatDateTime(i.dueAt)}${i.grouped ? ' (grouped)' : ''}${i.status === 'DONE' ? ' - watered' : ''}`;
            cell.appendChild(chip);
        });
        grid.appendChild(cell);
    }
}

function iso(d) {
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

el('prevMonth').addEventListener('click', () => {
    shownMonth.setMonth(shownMonth.getMonth() - 1);
    loadMonth();
});
el('nextMonth').addEventListener('click', () => {
    shownMonth.setMonth(shownMonth.getMonth() + 1);
    loadMonth();
});

function refreshAll() {
    loadOverview();
    loadAvailability();
    loadMonth();
}

document.addEventListener('po:watered', refreshAll);
document.addEventListener('po:changed', refreshAll);
refreshAll();
