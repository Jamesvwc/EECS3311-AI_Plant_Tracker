// F01 - Plant Identifier (GUI side).
//
// Flow (Stage 1 workflow):
//   1-3. "Upload Photo" -> POST /api/identify (multipart) -> top 3 candidates
//        (or the text path: POST /api/identify/text with a description)
//   4.   user picks one -> POST /api/identify/confirm
//   4a.  none match    -> "Add more photos" appends photos and re-identifies
//   5-6. the server looks up care info (Perenual, retry, Cohere fallback) and
//        the agent's explanation, with placement advice for the user's spaces.
//   +    "I own this plant" -> POST /api/plants (F08 + F02 schedule + F04 opt-in)
//        "Add to wishlist"  -> POST /api/desired (F09)
//
// All text from the server is inserted with textContent (never innerHTML).

const MAX_PHOTOS = 5;

const SOURCE_LABELS = {
    PERENUAL: 'Source: Perenual plant database',
    AI_GENERATED: 'Source: AI-generated from web results (Cohere), please double-check',
};

const { el, textEl } = PO;

const state = {
    photos: [],          // File objects the user selected (sent together)
    appendNext: false,   // true when the next selection adds to the existing photos
    candidates: [],
    lastRequest: null,   // repeats the last identification on "Try again"
    report: null,
    candidate: null,
};

// ---------- Upload ----------

el('uploadBtn').addEventListener('click', () => {
    state.appendNext = false;
    el('photoInput').click();
});

el('addPhotosBtn').addEventListener('click', () => {
    if (state.photos.length >= MAX_PHOTOS) {
        showError(`You already have ${MAX_PHOTOS} photos, the most the identifier accepts. Start over to use different photos.`, null);
        return;
    }
    state.appendNext = true;
    el('photoInput').click();
});

el('photoInput').addEventListener('change', (event) => {
    const chosen = Array.from(event.target.files || []);
    event.target.value = ''; // lets the user pick the same file again later
    if (chosen.length === 0) return;

    const combined = state.appendNext ? state.photos.concat(chosen) : chosen;
    if (combined.length > MAX_PHOTOS) {
        alert(`Only the first ${MAX_PHOTOS} photos will be used.`);
    }
    state.photos = combined.slice(0, MAX_PHOTOS);
    renderThumbnails();
    identify();
});

el('describeForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const description = el('describeInput').value.trim();
    if (description) identifyByText(description);
});

function renderThumbnails() {
    const thumbs = el('thumbs');
    thumbs.querySelectorAll('img').forEach((img) => URL.revokeObjectURL(img.src));
    thumbs.replaceChildren();
    state.photos.forEach((file) => {
        const img = document.createElement('img');
        img.src = URL.createObjectURL(file);
        img.alt = file.name;
        img.title = file.name;
        thumbs.appendChild(img);
    });
}

// ---------- Steps 1-3: identify ----------

async function identify() {
    state.lastRequest = identify;
    showOnly('upload');
    setStatus(`Resizing ${plural(state.photos.length, 'photo')} and asking Pl@ntNet...`);

    const form = new FormData();
    state.photos.forEach((file) => form.append('photos', file, file.name));

    try {
        const result = await PO.api('/api/identify', { method: 'POST', body: form });
        renderCandidates(result, true);
    } catch (err) {
        showError(err.message, err.retryable ? identify : null);
    }
}

async function identifyByText(description) {
    state.lastRequest = () => identifyByText(description);
    state.photos = [];
    renderThumbnails();
    showOnly('upload');
    setStatus('The agent is proposing species, checking them and finding photos...');
    try {
        const result = await PO.api('/api/identify/text', { method: 'POST', json: { description } });
        renderCandidates(result, false);
    } catch (err) {
        showError(err.message, err.retryable ? state.lastRequest : null);
    }
}

function renderCandidates(result, fromPhotos) {
    state.candidates = result.candidates;
    const list = el('candidates');
    list.replaceChildren();

    // The notes say why a guess was left out or unchecked, so they are shown whether or not anything matched.
    const notes = result.notes && result.notes.length ? ' ' + result.notes.join(' ') : '';
    if (result.candidates.length === 0) {
        el('candidatesHint').textContent = (fromPhotos
            ? "We couldn't recognise a plant in your photo. Try adding a clearer photo of a leaf or flower."
            : "None of the agent's guesses could be confirmed. Add more detail (leaf shape, size, flowers) and try again.")
            + notes;
    } else {
        el('candidatesHint').textContent =
            `Top ${plural(result.candidates.length, 'match', 'matches')}` +
            (fromPhotos ? ` from ${plural(result.photosUsed, 'photo')}. ` : ' for your description. ') +
            'Compare the reference photos with your plant.' +
            (result.remainingRequests != null ? ` (${result.remainingRequests} identifications left today)` : '') +
            notes;
    }
    el('addPhotosBtn').hidden = !fromPhotos;

    result.candidates.forEach((candidate, index) => {
        const card = textEl('article', '', 'candidate');
        card.appendChild(PO.image(candidate.referenceImageUrl, `Reference photo of ${candidate.scientificName}`));

        const body = textEl('div', '', 'candidate-body');
        body.appendChild(textEl('h4', candidate.commonNames[0] || candidate.scientificName));
        body.appendChild(textEl('p', candidate.scientificName, 'scientific'));
        if (candidate.family) body.appendChild(textEl('p', `Family: ${candidate.family}`, 'family'));

        const percent = Math.round(candidate.score * 100);
        const meter = textEl('div', '', 'confidence');
        meter.setAttribute('role', 'meter');
        meter.setAttribute('aria-valuenow', String(percent));
        meter.setAttribute('aria-valuemin', '0');
        meter.setAttribute('aria-valuemax', '100');
        const bar = document.createElement('span');
        bar.style.width = `${Math.max(percent, 2)}%`;
        meter.appendChild(bar);
        body.appendChild(meter);
        body.appendChild(textEl('p', `${percent}% confidence`, 'confidence-label'));

        body.appendChild(PO.button('This is my plant', index === 0 ? 'primary-btn' : 'secondary-btn',
            () => confirmCandidate(candidate)));
        card.appendChild(body);
        list.appendChild(card);
    });

    showOnly('candidates');
}

// ---------- Steps 4-6: confirm + care report ----------

async function confirmCandidate(candidate) {
    showOnly('upload');
    setStatus(`Looking up care information for ${candidate.scientificName}...`);
    try {
        const report = await PO.api('/api/identify/confirm', {
            method: 'POST',
            json: { scientificName: candidate.scientificName, commonName: candidate.commonNames[0] || null },
        });
        renderReport(report, candidate);
    } catch (err) {
        showError(err.message, err.retryable ? () => confirmCandidate(candidate) : null);
    }
}

function renderReport(report, candidate) {
    const p = report.profile;
    state.report = report;
    state.candidate = candidate;

    el('reportTitle').textContent = p.commonName || candidate.commonNames[0] || p.scientificName;
    el('reportScientific').textContent = p.scientificName;

    const imageUrl = p.imageUrl || candidate.referenceImageUrl;
    const img = el('reportImage');
    img.hidden = !imageUrl;
    img.onerror = () => { img.hidden = true; };
    if (imageUrl) {
        img.src = imageUrl;
        img.alt = `Photo of ${p.scientificName}`;
    }

    const badge = el('reportSource');
    badge.textContent = SOURCE_LABELS[p.source] || p.source;
    badge.classList.toggle('badge-warn', p.source === 'AI_GENERATED');

    const similar = el('similarWarning');
    similar.hidden = !p.similarPlantName;
    similar.textContent = p.similarPlantName
        ? `Care information for ${candidate.scientificName} couldn't be found, so this describes a similar plant: ` +
          `${p.similarPlantName}. Try again with more photos for a closer match.`
        : '';

    const facts = el('reportFacts');
    facts.replaceChildren();
    addFact(facts, 'Watering', joinParts(p.watering, p.wateringFrequency));
    addFact(facts, 'Sunlight', p.sunlight.length ? p.sunlight.join(', ') : null);
    addFact(facts, 'Care level', p.careLevel);
    addFact(facts, 'Growth rate', p.growthRate);
    addFact(facts, 'Indoor plant', yesNo(p.indoor));
    addFact(facts, 'Toxic to pets', yesNo(p.poisonousToPets));
    addFact(facts, 'Toxic to humans', yesNo(p.poisonousToHumans));
    addFact(facts, 'Hardiness', p.hardinessZones);
    addFact(facts, 'Family', p.family);

    el('reportDescription').textContent = p.description || '';
    el('reportDescription').hidden = !p.description;

    el('explanationHeading').textContent = report.aiExplanation
        ? '\u{1F916} How to care for it and where to put it (AI agent)'
        : '\u{1F4CB} Care summary';
    PO.renderRichText(el('reportExplanation'), report.explanation);

    const notes = el('reportNotes');
    notes.replaceChildren(...report.notes.map((n) => textEl('li', n)));
    el('reportNotesWrap').hidden = report.notes.length === 0;

    el('ownResult').hidden = true;
    el('ownBtn').disabled = false;
    el('wishBtn').disabled = false;
    loadSpaces();
    showOnly('report');
}

// ---------- F08 / F09: own it or wish for it ----------

async function loadSpaces() {
    const select = el('ownSpace');
    select.replaceChildren(new Option('Not placed yet', ''));
    try {
        const add = (spaces) => spaces.forEach((s) => {
            select.appendChild(new Option(s.path + (s.effectiveLight ? ` (${s.effectiveLight})` : ''), s.id));
            add(s.children);
        });
        add(await PO.api('/api/spaces'));
    } catch (err) {
        // spaces are optional here
    }
}

el('ownBtn').addEventListener('click', (e) => PO.busy(e.target, async () => {
    const p = state.report.profile;
    const result = await PO.api('/api/plants', {
        method: 'POST',
        json: {
            scientificName: p.scientificName,
            commonName: p.commonName || state.candidate.commonNames[0] || null,
            nickname: el('ownNickname').value.trim() || null,
            spaceId: el('ownSpace').value ? Number(el('ownSpace').value) : null,
        },
    });
    el('ownBtn').disabled = true;
    renderOwned(result);
}));

el('wishBtn').addEventListener('click', (e) => PO.busy(e.target, async () => {
    const p = state.report.profile;
    const d = await PO.api('/api/desired', {
        method: 'POST',
        json: {
            scientificName: p.scientificName,
            commonName: p.commonName || state.candidate.commonNames[0] || null,
            spaceId: el('ownSpace').value ? Number(el('ownSpace').value) : null,
            imageUrl: p.imageUrl || state.candidate.referenceImageUrl || null,
        },
    });
    el('wishBtn').disabled = true;
    PO.toast(`${d.commonName || d.scientificName} added to your wishlist.`);
}));

function renderOwned(result) {
    const box = el('ownResult');
    box.hidden = false;
    box.replaceChildren(textEl('h4', `✅ ${result.plantName} added to My Plants` +
        (result.location ? ` (${result.location})` : '')));
    const s = result.schedule;
    const text = textEl('div', '', 'explanation');
    PO.renderRichText(text, s.explanation);
    box.appendChild(text);
    if (s.nextWaterings.length) {
        box.appendChild(textEl('p', 'Next waterings: ' + s.nextWaterings.slice(0, 3).map(PO.formatDateTime).join(' · '), 'hint'));
    }
    result.notes.forEach((n) => box.appendChild(textEl('p', n, 'notice')));

    const row = textEl('div', '', 'panel-row');
    row.style.marginTop = '12px';
    if (s.askAboutNotifications) {
        box.appendChild(textEl('p', 'Would you like reminders when your plants need water?'));
        row.appendChild(PO.button('Yes, remind me', 'primary-btn', (e) => setReminders(e.target, true, result.plantId, row)));
        row.appendChild(PO.button('No reminders', 'secondary-btn', (e) => setReminders(e.target, false, result.plantId, row)));
    }
    if (s.dataWarning) {
        row.appendChild(PO.button('\u{1F50E} Search for more info', 'secondary-btn', (e) => PO.busy(e.target, async () => {
            const updated = await PO.api(`/api/plants/${result.plantId}/schedule/research`, { method: 'POST' });
            PO.renderRichText(text, updated.explanation);
            e.target.remove();
        })));
    }
    const open = textEl('a', 'Open in My Plants →', 'panel-link');
    open.href = `currentlistofplants.html?plant=${result.plantId}`;
    box.appendChild(row);
    box.appendChild(open);
}

async function setReminders(btn, enabled, plantId, row) {
    if (enabled && 'Notification' in window && Notification.permission === 'default') {
        Notification.requestPermission();
    }
    await PO.busy(btn, () => PO.api('/api/notifications/default', { method: 'POST', json: { enabled, plantId } }));
    row.querySelectorAll('button').forEach((b) => { if (b.textContent.includes('remind')) b.remove(); });
    PO.toast(enabled ? 'Reminders are on. New plants will get them too.' : 'No reminders. You can turn them on per plant later.');
}

// ---------- Navigation buttons ----------

el('startOverBtn').addEventListener('click', resetAll);
el('identifyAnotherBtn').addEventListener('click', resetAll);
el('backToCandidatesBtn').addEventListener('click', () => showOnly('candidates'));

function resetAll() {
    state.photos = [];
    state.candidates = [];
    el('describeInput').value = '';
    el('ownNickname').value = '';
    renderThumbnails();
    showOnly('upload');
}

// ---------- Helpers ----------

let retryAction = null;
el('retryBtn').addEventListener('click', () => retryAction && retryAction());

function showError(message, retry) {
    setStatus(null);
    retryAction = retry;
    el('errorText').textContent = message;
    el('retryBtn').hidden = !retry;
    el('errorBox').hidden = false;
}

function setStatus(message) {
    el('errorBox').hidden = true;
    el('status').hidden = !message;
    el('statusText').textContent = message || '';
    el('uploadBtn').disabled = !!message;
    el('addPhotosBtn').disabled = !!message;
    el('describeBtn').disabled = !!message;
}

// Shows one step of the flow: 'upload', 'candidates' or 'report'.
function showOnly(step) {
    if (step !== 'upload') setStatus(null);
    el('uploadPanel').hidden = step === 'report';
    el('candidatesPanel').hidden = step !== 'candidates';
    el('reportPanel').hidden = step !== 'report';
    if (step === 'upload') el('errorBox').hidden = true;
}

function addFact(list, label, value) {
    list.appendChild(textEl('dt', label));
    list.appendChild(textEl('dd', value || 'Unknown', value ? '' : 'unknown'));
}

function plural(n, one, many) {
    return `${n} ${n === 1 ? one : (many || one + 's')}`;
}

function yesNo(value) {
    if (value === null || value === undefined) return null;
    return value ? 'Yes' : 'No';
}

function joinParts(a, b) {
    if (!a) return b || null;
    return b ? `${a} (${b})` : a;
}
