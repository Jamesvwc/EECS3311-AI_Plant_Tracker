// F08 - List of Owned Plants, plus per-plant F02 (schedule), F04 (watered /
// reminders), F05 (health checks) and F10 (location).

const { el, textEl, api } = PO;

const state = { plants: [], spaces: [], current: null };

async function load() {
    try {
        const [plants, spaces] = await Promise.all([api('/api/plants'), api('/api/spaces')]);
        state.plants = plants;
        state.spaces = flatten(spaces);
        renderGrid();
        fillSpaceSelect(el('addSpace'), null);
        const wanted = new URLSearchParams(location.search).get('plant');
        if (wanted && !el('plantDialog').open) openPlant(Number(wanted));
    } catch (err) {
        el('plantGrid').replaceChildren(textEl('p', err.message, 'error-text'));
    } finally {
        el('status').hidden = true;
    }
}

function flatten(spaces, out = []) {
    spaces.forEach((s) => {
        out.push(s);
        flatten(s.children, out);
    });
    return out;
}

function fillSpaceSelect(select, selectedId) {
    select.replaceChildren(new Option('Not placed', ''));
    state.spaces.forEach((s) => {
        const o = new Option(s.path + (s.effectiveLight ? ` (${s.effectiveLight})` : ''), s.id);
        o.selected = s.id === selectedId;
        select.appendChild(o);
    });
}

function renderGrid() {
    const grid = el('plantGrid');
    grid.replaceChildren();
    el('emptyState').hidden = state.plants.length > 0;
    state.plants.forEach((p) => {
        const card = textEl('article', '', 'card plant-card status-card-' + p.wateringStatus.toLowerCase());
        card.appendChild(PO.image(p.care.imageUrl, p.name));
        const body = textEl('div', '', 'card-body');
        const title = textEl('h4', p.name);
        body.appendChild(title);
        body.appendChild(textEl('p', p.scientificName, 'scientific'));
        const badges = textEl('div', '', 'panel-row');
        badges.appendChild(PO.statusBadge(p.wateringStatus));
        if (p.latestHealth && p.latestHealth !== 'HEALTHY') {
            badges.appendChild(textEl('span', '\u{1FA7A} ' + p.latestHealth.replace('_', ' ').toLowerCase(),
                'badge ' + (p.latestHealth === 'MINOR_ISSUES' ? 'badge-warn' : 'badge-bad')));
        }
        body.appendChild(badges);
        body.appendChild(textEl('p', '\u{1F4CD} ' + (p.location || 'Not placed yet'), 'meta'));
        body.appendChild(textEl('p', p.nextWatering ? '\u{1F4A7} Next: ' + PO.formatDateTime(p.nextWatering)
            : '\u{1F4A7} No watering planned', 'meta'));
        if (p.intervalQuality && p.intervalQuality !== 'GOOD') {
            body.appendChild(textEl('p', '⚠️ Watering interval is ' +
                (p.intervalQuality === 'MISSING' ? 'a default guess' : 'estimated'), 'meta'));
        }
        const toggle = textEl('label', '', 'toggle');
        const box = document.createElement('input');
        box.type = 'checkbox';
        box.checked = p.notificationsEnabled;
        box.addEventListener('change', () => setReminders(p.id, box.checked, box));
        toggle.append(box, document.createTextNode(' Reminders'));
        body.appendChild(toggle);
        const actions = textEl('div', '', 'card-actions');
        actions.appendChild(PO.button('\u{1F4A7} Watered', 'mini-btn', (e) => water(p.id, e.target)));
        actions.appendChild(PO.button('Details', 'mini-btn', () => openPlant(p.id)));
        body.appendChild(actions);
        card.appendChild(body);
        grid.appendChild(card);
    });
}

async function water(plantId, btn) {
    const r = await PO.busy(btn, () => api(`/api/plants/${plantId}/water`, { method: 'POST' }));
    if (r) {
        PO.toast(r.message);
        await reload(plantId);
    }
}

async function setReminders(plantId, enabled, box) {
    if (enabled && 'Notification' in window && Notification.permission === 'default') {
        Notification.requestPermission();
    }
    try {
        await api(`/api/plants/${plantId}/notifications`, { method: 'PUT', json: { enabled } });
        PO.toast(enabled ? 'Reminders on.' : 'Reminders off.');
        const p = state.plants.find((x) => x.id === plantId);
        if (p) p.notificationsEnabled = enabled;
    } catch (err) {
        box.checked = !enabled;
        PO.toast(err.message, 'error');
    }
}

async function reload(openId) {
    const [plants, spaces] = await Promise.all([api('/api/plants'), api('/api/spaces')]);
    state.plants = plants;
    state.spaces = flatten(spaces);
    renderGrid();
    if (openId && el('plantDialog').open && state.current && state.current.id === openId) {
        state.current = plants.find((p) => p.id === openId);
        if (state.current) renderDialog();
    }
}

// ---------- Add manually (UC02 "Add plant") ----------

el('addForm').addEventListener('submit', (e) => {
    e.preventDefault();
    PO.busy(el('addBtn'), async () => {
        const result = await api('/api/plants', {
            method: 'POST',
            json: {
                scientificName: el('addScientific').value.trim(),
                commonName: el('addCommon').value.trim() || null,
                nickname: el('addNickname').value.trim() || null,
                spaceId: el('addSpace').value ? Number(el('addSpace').value) : null,
            },
        });
        el('addForm').reset();
        const box = el('addResult');
        box.replaceChildren(textEl('h4', `✅ Added ${result.plantName}`));
        const text = textEl('div', '', 'explanation');
        PO.renderRichText(text, result.schedule.explanation);
        box.appendChild(text);
        result.notes.forEach((n) => box.appendChild(textEl('p', n, 'notice')));
        if (result.schedule.askAboutNotifications) {
            const row = textEl('div', '', 'panel-row');
            row.appendChild(textEl('span', 'Would you like watering reminders?'));
            [['Yes, remind me', true], ['No reminders', false]].forEach(([label, on]) =>
                row.appendChild(PO.button(label, on ? 'primary-btn' : 'secondary-btn', async (ev) => {
                    await PO.busy(ev.target, () => api('/api/notifications/default',
                        { method: 'POST', json: { enabled: on, plantId: result.plantId } }));
                    row.remove();
                    reload();
                })));
            box.appendChild(row);
        }
        await reload();
    });
});

// ---------- Details dialog ----------

el('dialogClose').addEventListener('click', () => el('plantDialog').close());
document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));

function showTab(name) {
    document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
    document.querySelectorAll('[data-panel]').forEach((p) => (p.hidden = p.dataset.panel !== name));
    if (name === 'notes') loadNotes();
    if (name === 'health') loadHealthHistory();
}

function openPlant(id) {
    state.current = state.plants.find((p) => p.id === id);
    if (!state.current) return;
    el('healthResult').replaceChildren();
    el('scheduleResult').replaceChildren();
    renderDialog();
    showTab('care');
    el('plantDialog').showModal();
}

function renderDialog() {
    const p = state.current;
    el('dlgImage').replaceChildren(PO.image(p.care.imageUrl, p.name));
    el('dlgName').textContent = p.name;
    el('dlgScientific').textContent = p.scientificName + (p.commonName && p.commonName !== p.name ? ` · ${p.commonName}` : '');
    const badges = el('dlgBadges');
    badges.replaceChildren(PO.statusBadge(p.wateringStatus));
    if (p.location) badges.appendChild(textEl('span', '\u{1F4CD} ' + p.location, 'badge badge-neutral'));

    // Care notes form
    const f = el('careForm');
    const c = p.care;
    f.nickname.value = p.nickname || '';
    ['watering', 'wateringFrequency', 'careLevel', 'growthRate', 'hardinessZones', 'family', 'description']
        .forEach((k) => (f[k].value = c[k] || ''));
    f.sunlight.value = (c.sunlight || []).join(', ');
    ['indoor', 'poisonousToPets', 'poisonousToHumans'].forEach((k) => (f[k].value = c[k] === null || c[k] === undefined ? '' : String(c[k])));
    el('careSource').textContent = c.source ? 'Care notes started from: ' + (c.source === 'PERENUAL'
        ? 'the Perenual plant database' : 'AI-generated web research (double-check)') : 'Care notes entered by you.';

    // Schedule tab
    const facts = el('scheduleFacts');
    facts.replaceChildren();
    const fact = (k, v) => {
        facts.appendChild(textEl('dt', k));
        facts.appendChild(textEl('dd', v || 'Unknown', v ? '' : 'unknown'));
    };
    fact('Water every', p.wateringIntervalDays ? `${p.wateringIntervalDays} days` : null);
    fact('Interval based on', { GOOD: 'care data', ESTIMATED: 'an estimate from the watering category', MISSING: 'a 7-day default (no data found)' }[p.intervalQuality]);
    fact('Next watering', p.nextWatering ? PO.formatDateTime(p.nextWatering) : null);
    fact('Last watered', p.lastWateredAt ? PO.formatDateTime(p.lastWateredAt) : null);
    el('researchBtn').hidden = !p.intervalQuality || p.intervalQuality === 'GOOD';
    el('notifyToggle').checked = p.notificationsEnabled;
    fillSpaceSelect(el('spaceSelect'), p.spaceId);
}

el('careForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const f = e.target;
    const bool = (v) => (v === '' ? null : v === 'true');
    const care = {
        watering: f.watering.value, wateringFrequency: f.wateringFrequency.value,
        sunlight: f.sunlight.value.split(',').map((s) => s.trim()).filter(Boolean),
        careLevel: f.careLevel.value, growthRate: f.growthRate.value, hardinessZones: f.hardinessZones.value,
        family: f.family.value, description: f.description.value,
        indoor: bool(f.indoor.value), poisonousToPets: bool(f.poisonousToPets.value), poisonousToHumans: bool(f.poisonousToHumans.value),
    };
    PO.busy(f.querySelector('button[type=submit]'), async () => {
        await api(`/api/plants/${state.current.id}`, { method: 'PUT', json: { nickname: f.nickname.value, care } });
        PO.toast('Care notes saved.');
        await reload(state.current.id);
    });
});

// ---------- Notes ----------

async function loadNotes() {
    const list = el('noteList');
    list.replaceChildren(textEl('li', 'Loading...', 'muted'));
    try {
        const notes = await api(`/api/plants/${state.current.id}/notes`);
        list.replaceChildren();
        if (!notes.length) list.appendChild(textEl('li', 'No notes yet.', 'muted'));
        notes.forEach((n) => {
            const li = textEl('li', '');
            const meta = textEl('div', '', 'note-meta');
            meta.appendChild(textEl('span', { USER: 'You', AGENT: 'Agent', HEALTH: 'Health check' }[n.kind] + ' · ' + PO.formatDateTime(n.createdAt)));
            meta.appendChild(PO.button('Delete', 'link-btn', async () => {
                await api(`/api/plants/${state.current.id}/notes/${n.id}`, { method: 'DELETE' });
                loadNotes();
            }));
            li.appendChild(meta);
            li.appendChild(textEl('div', n.text));
            list.appendChild(li);
        });
    } catch (err) {
        list.replaceChildren(textEl('li', err.message, 'error-text'));
    }
}

el('noteForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const text = el('noteText').value.trim();
    if (!text) return;
    PO.busy(e.target.querySelector('button'), async () => {
        await api(`/api/plants/${state.current.id}/notes`, { method: 'POST', json: { text } });
        el('noteText').value = '';
        loadNotes();
    });
});

// ---------- Health (F05) ----------

el('healthForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const form = new FormData();
    const text = el('healthText').value.trim();
    const photo = el('healthPhoto').files[0];
    if (!text && !photo) {
        PO.toast('Describe the problem or add a photo.', 'error');
        return;
    }
    if (text) form.append('description', text);
    if (photo) form.append('photo', photo, photo.name);
    const result = el('healthResult');
    result.replaceChildren(statusLine('The agent is examining your plant...'));
    PO.busy(el('healthBtn'), async () => {
        try {
            const report = await api(`/api/plants/${state.current.id}/health`, { method: 'POST', body: form });
            renderHealth(report);
            el('healthText').value = '';
            el('healthPhoto').value = '';
            loadHealthHistory();
            reload(state.current.id);
        } catch (err) {
            result.replaceChildren(textEl('p', err.message, 'error-box'));
        }
    });
});

function statusLine(text) {
    const s = textEl('div', '', 'status');
    s.append(textEl('span', '', 'spinner'), textEl('span', text));
    return s;
}

function renderHealth(report) {
    const r = report.record;
    const box = textEl('div', '', 'health-report');
    const head = textEl('div', '', 'panel-row');
    head.appendChild(textEl('strong', `Health ${r.healthScore}/10`));
    head.appendChild(textEl('span', r.status.replace('_', ' ').toLowerCase(), 'badge ' + healthClass(r.status)));
    if (r.trend !== 'FIRST_CHECK') head.appendChild(textEl('span', r.trend.toLowerCase() + ' since last check', 'badge badge-neutral'));
    box.appendChild(head);
    if (report.speciesWarning) box.appendChild(textEl('p', '⚠️ ' + report.speciesWarning, 'notice'));
    const body = textEl('div', '', 'explanation');
    PO.renderRichText(body, `${r.summary || ''}\n\n**What I see:**\n${bullets(r.issues)}\n\n**What to do:**\n${bullets(r.careSteps)}`
        + (r.comparison ? `\n\n**Compared with last time:** ${r.comparison}` : ''));
    box.appendChild(body);
    report.notes.forEach((n) => box.appendChild(textEl('p', n, 'muted')));
    const row = textEl('div', '', 'panel-row');
    row.style.marginTop = '10px';
    row.appendChild(PO.button('Save to notes', 'secondary-btn', async (e) => {
        await PO.busy(e.target, () => api(`/api/plants/health/${r.id}/save-note`, { method: 'POST' }));
        e.target.disabled = true;
        PO.toast('Saved to notes.');
    }));
    row.appendChild(PO.button('Not satisfied? Dig deeper', 'secondary-btn', (e) => digDeeper(r.id, box, e.target)));
    box.appendChild(row);
    el('healthResult').replaceChildren(box);
}

async function digDeeper(recordId, box, btn) {
    const status = statusLine('Searching the web for more...');
    box.appendChild(status);
    const research = await PO.busy(btn, () => api(`/api/plants/health/${recordId}/research`, { method: 'POST' }));
    status.remove();
    if (!research) return;
    btn.disabled = true;
    const more = textEl('div', '', 'explanation');
    more.style.marginTop = '12px';
    PO.renderRichText(more, research.answer);
    box.appendChild(more);
    if (research.sources.length) {
        const src = textEl('p', 'Sources: ', 'muted');
        research.sources.forEach((s, i) => {
            const a = textEl('a', `[${i + 1}] ${s.title}`);
            a.href = s.url;
            a.target = '_blank';
            a.rel = 'noopener';
            src.append(a, ' ');
        });
        box.appendChild(src);
    }
    const row = textEl('div', '', 'panel-row');
    row.appendChild(PO.button('Save this to notes', 'secondary-btn', async (e) => {
        await PO.busy(e.target, () => api(`/api/plants/${state.current.id}/agent-notes`, { method: 'POST', json: { text: research.answer } }));
        e.target.disabled = true;
        PO.toast('Saved to notes.');
    }));
    row.appendChild(PO.button('Still not helpful', 'link-btn', () => {
        // UC06: "Still unsatisfied: agent suggests the user add their own notes"
        showTab('notes');
        el('noteText').placeholder = 'You know your plant best: what have you noticed? (watering, light changes, pests, repotting...)';
        el('noteText').focus();
        PO.toast('Add what you know to its notes so the next check has more to go on.');
    }));
    box.appendChild(row);
}

async function loadHealthHistory() {
    const list = el('healthHistory');
    try {
        const records = await api(`/api/plants/${state.current.id}/health`);
        list.replaceChildren();
        if (!records.length) list.appendChild(textEl('p', 'No health checks yet.', 'muted'));
        records.forEach((r) => {
            const item = textEl('div', '', 'history-item');
            if (r.photoId) {
                const img = document.createElement('img');
                img.src = `/api/plants/${state.current.id}/photos/${r.photoId}?user=${PO.userId()}`;
                img.alt = 'Photo from ' + PO.formatDate(r.createdAt);
                item.appendChild(img);
            }
            const text = textEl('div', '');
            text.appendChild(textEl('p', `${PO.formatDate(r.createdAt)} · ${r.healthScore}/10 · ${r.status.replace('_', ' ').toLowerCase()}`
                + (r.trend !== 'FIRST_CHECK' ? ` · ${r.trend.toLowerCase()}` : '')));
            text.appendChild(textEl('p', r.summary || '', 'muted'));
            item.appendChild(text);
            list.appendChild(item);
        });
    } catch (err) {
        list.replaceChildren(textEl('p', err.message, 'error-text'));
    }
}

function healthClass(status) {
    return { HEALTHY: 'badge-good', MINOR_ISSUES: 'badge-warn' }[status] || 'badge-bad';
}

function bullets(lines) {
    return (lines || '').split('\n').filter((l) => l.trim()).map((l) => '- ' + l.trim()).join('\n') || '- (none noted)';
}

// ---------- Schedule & location ----------

el('waterBtn').addEventListener('click', (e) => water(state.current.id, e.target));

el('regenBtn').addEventListener('click', (e) => PO.busy(e.target, async () => {
    const s = await api(`/api/plants/${state.current.id}/schedule`, { method: 'POST' });
    showSchedule(s);
}));

el('researchBtn').addEventListener('click', (e) => PO.busy(e.target, async () => {
    el('scheduleResult').replaceChildren(statusLine('Researching watering needs on the web...'));
    const s = await api(`/api/plants/${state.current.id}/schedule/research`, { method: 'POST' });
    showSchedule(s);
}));

function showSchedule(s) {
    const box = textEl('div', '', 'explanation');
    box.style.marginTop = '12px';
    PO.renderRichText(box, s.explanation);
    el('scheduleResult').replaceChildren(box);
    reload(state.current.id);
}

el('notifyToggle').addEventListener('change', (e) => setReminders(state.current.id, e.target.checked, e.target));

el('placeBtn').addEventListener('click', (e) => PO.busy(e.target, async () => {
    const spaceId = el('spaceSelect').value;
    if (spaceId) {
        await api(`/api/spaces/${spaceId}/plants/${state.current.id}`, { method: 'POST' });
    } else {
        await api(`/api/spaces/plants/${state.current.id}`, { method: 'DELETE' });
    }
    PO.toast('Location updated.');
    await reload(state.current.id);
}));

el('deleteBtn').addEventListener('click', async () => {
    if (!confirm(`Delete ${state.current.name}? Its notes, photos, health history and schedule are deleted too.`)) return;
    await PO.busy(el('deleteBtn'), () => api(`/api/plants/${state.current.id}`, { method: 'DELETE' }));
    el('plantDialog').close();
    await reload();
});

document.addEventListener('po:watered', () => reload());
document.addEventListener('po:changed', () => reload());
load();
