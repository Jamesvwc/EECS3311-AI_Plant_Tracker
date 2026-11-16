// F06 - automatic recommendations (learned preferences) and
// F07 - recommendations for constraints the user types.

const { el, textEl, api } = PO;

async function loadList() {
    el('status').hidden = false;
    try {
        const list = await api(`/api/recommendations?sort=${el('sortSelect').value}`);
        el('listMessage').textContent = list.message || (list.refreshed ? 'Refreshed just now from what the agent has learned.' : '');
        renderCards(el('recGrid'), list.items, false);
        if (!list.items.length && !list.message) {
            el('listMessage').textContent = 'No recommendations yet. Press Refresh, or ask for something specific above.';
        }
    } catch (err) {
        el('listMessage').textContent = err.message;
    } finally {
        el('status').hidden = true;
        loadPreferences();
    }
}

function renderCards(grid, items, suggestions) {
    grid.replaceChildren();
    items.forEach((r) => {
        const card = textEl('article', '', 'card');
        card.appendChild(PO.image(r.imageUrl, r.commonName || r.scientificName));
        const body = textEl('div', '', 'card-body');
        body.appendChild(textEl('h4', r.commonName || r.scientificName));
        body.appendChild(textEl('p', r.scientificName, 'scientific'));
        const badges = textEl('div', '', 'panel-row');
        if (r.careLevel) badges.appendChild(textEl('span', r.careLevel + ' care', 'badge ' + ({ 1: 'badge-good', 2: 'badge-warn', 3: 'badge-bad' }[r.careRank])));
        badges.appendChild(textEl('span', r.verified ? '✓ In Perenual' : 'AI suggestion', 'badge ' + (r.verified ? 'badge-good' : 'badge-neutral')));
        if (r.petSafe === true) badges.appendChild(textEl('span', 'Pet safe', 'badge badge-good'));
        body.appendChild(badges);
        if (r.recommendedSpace) body.appendChild(textEl('p', '\u{1F4CD} ' + r.recommendedSpace, 'family'));
        if (r.sunlight || r.watering) body.appendChild(textEl('p', [r.sunlight, r.watering && 'water: ' + r.watering].filter(Boolean).join(' · '), 'family'));
        body.appendChild(textEl('p', r.reason, 'reason'));
        const actions = textEl('div', '', 'card-actions');
        if (suggestions) {
            actions.appendChild(PO.button('Keep', 'mini-btn', async (e) => {
                await PO.busy(e.target, () => api(`/api/recommendations/${r.id}/accept`, { method: 'POST' }));
                card.remove();
                PO.toast(`${r.commonName || r.scientificName} added to your recommendations.`);
                loadList();
            }));
        } else {
            actions.appendChild(PO.button('⭐ Add to wishlist', 'mini-btn', (e) => PO.busy(e.target, async () => {
                await api('/api/desired', { method: 'POST', json: { scientificName: r.scientificName, commonName: r.commonName, imageUrl: r.imageUrl } });
                e.target.disabled = true;
                PO.toast('Added to your wishlist.');
            })));
        }
        actions.appendChild(PO.button(suggestions ? 'Not for me' : 'Unrecommend', 'mini-btn', async (e) => {
            await PO.busy(e.target, () => api(`/api/recommendations/${r.id}`, { method: 'DELETE' }));
            card.remove();
            PO.toast("Got it. The agent won't suggest that again.");
            loadPreferences();
        }));
        body.appendChild(actions);
        card.appendChild(body);
        grid.appendChild(card);
    });
}

el('sortSelect').addEventListener('change', loadList);

el('refreshBtn').addEventListener('click', (e) => PO.busy(e.target, async () => {
    el('status').hidden = false;
    try {
        await api('/api/recommendations/refresh', { method: 'POST' });
        await loadList();
    } finally {
        el('status').hidden = true;
    }
}));

el('requestForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const constraints = el('requestText').value.trim();
    if (!constraints) return;
    const box = el('requestResult');
    const waiting = textEl('div', '', 'status');
    waiting.append(textEl('span', '', 'spinner'), textEl('span', 'The agent is finding plants that fit...'));
    box.replaceChildren(waiting);
    PO.busy(el('requestBtn'), async () => {
        try {
            const items = await api('/api/recommendations/request', { method: 'POST', json: { constraints } });
            if (!items.length) {
                box.replaceChildren(textEl('p', 'No plant met all of that. Try relaxing one constraint (e.g. allow medium care or a brighter spot).', 'notice'));
                return;
            }
            const grid = textEl('div', '', 'card-grid suggestions');
            renderCards(grid, items, true);
            box.replaceChildren(textEl('p', 'Keep the ones you like; they join your list below.', 'hint'), grid);
        } catch (err) {
            box.replaceChildren(textEl('p', err.message, 'error-box'));
        }
    });
});

async function loadPreferences() {
    const list = el('prefList');
    try {
        const prefs = await api('/api/recommendations/preferences');
        list.replaceChildren();
        if (!prefs.length) list.appendChild(textEl('li', 'Nothing yet.', 'muted'));
        prefs.forEach((p) => {
            const li = document.createElement('li');
            li.appendChild(textEl('span', { LIKE: '\u{1F44D} ', DISLIKE: '\u{1F44E} ', NOTE: '\u{1F4DD} ' }[p.kind] + p.subject));
            li.appendChild(PO.button('Forget', 'link-btn', async () => {
                await api(`/api/recommendations/preferences/${p.id}`, { method: 'DELETE' });
                loadPreferences();
            }));
            list.appendChild(li);
        });
    } catch (err) {
        list.replaceChildren(textEl('li', err.message, 'error-text'));
    }
}

document.addEventListener('po:changed', loadList);
loadList();
