// F09 - List of Desired Plants: intended space + the agent's fit feedback.

const { el, textEl, api } = PO;

let spaces = [];
const VERDICT = { GOOD: ['Good fit', 'badge-good'], FAIR: ['Could work', 'badge-warn'], POOR: ['Poor fit', 'badge-bad'], UNKNOWN: ["Can't judge yet", 'badge-neutral'] };

async function load() {
    try {
        const [items, tree] = await Promise.all([api('/api/desired'), api('/api/spaces')]);
        spaces = [];
        const walk = (list) => list.forEach((s) => { spaces.push(s); walk(s.children); });
        walk(tree);
        fillSpaces(el('addSpace'), null);
        render(items);
    } catch (err) {
        el('wishGrid').replaceChildren(textEl('p', err.message, 'error-text'));
    } finally {
        el('status').hidden = true;
    }
}

function fillSpaces(select, selected) {
    select.replaceChildren(new Option('Not decided', ''));
    spaces.forEach((s) => {
        const o = new Option(s.path + (s.effectiveLight ? ` (${s.effectiveLight})` : ''), s.id);
        o.selected = s.id === selected;
        select.appendChild(o);
    });
}

function render(items) {
    const grid = el('wishGrid');
    grid.replaceChildren();
    el('emptyState').hidden = items.length > 0;
    items.forEach((d) => {
        const card = textEl('article', '', 'card');
        card.appendChild(PO.image(d.imageUrl, d.commonName || d.scientificName));
        const body = textEl('div', '', 'card-body');
        body.appendChild(textEl('h4', d.commonName || d.scientificName));
        body.appendChild(textEl('p', d.scientificName, 'scientific'));
        if (d.note) body.appendChild(textEl('p', d.note, 'family'));

        const label = textEl('label', 'Where would it go?');
        const select = document.createElement('select');
        fillSpaces(select, d.intendedSpaceId);
        select.addEventListener('change', () => PO.busy(null, async () => {
            fitBox.replaceChildren(textEl('p', 'The agent is checking the fit...', 'muted'));
            await api(`/api/desired/${d.id}`, { method: 'PUT', json: { spaceId: select.value ? Number(select.value) : null, note: d.note } });
            load();
        }));
        label.appendChild(select);
        body.appendChild(label);

        const fitBox = textEl('div', '');
        if (d.fitVerdict) {
            const [text, cls] = VERDICT[d.fitVerdict];
            fitBox.appendChild(textEl('span', text, 'badge ' + cls));
            fitBox.appendChild(textEl('p', d.fitExplanation, 'reason'));
            if (d.fitVerdict === 'UNKNOWN') {
                // UC09 alternative: no care info, so the agent offers a search.
                fitBox.appendChild(PO.button('\u{1F50E} Search the web', 'mini-btn', (e) => PO.busy(e.target, async () => {
                    const r = await api(`/api/desired/${d.id}/research`, { method: 'POST' });
                    const box = textEl('div', '', 'explanation');
                    PO.renderRichText(box, r.answer);
                    fitBox.appendChild(box);
                    e.target.remove();
                })));
            }
        }
        body.appendChild(fitBox);

        const actions = textEl('div', '', 'card-actions');
        if (d.intendedSpaceId) {
            actions.appendChild(PO.button('Check fit again', 'mini-btn', (e) => PO.busy(e.target, async () => {
                await api(`/api/desired/${d.id}/fit`, { method: 'POST' });
                load();
            })));
        }
        actions.appendChild(PO.button('\u{1F6D2} I bought it', 'mini-btn', (e) => PO.busy(e.target, async () => {
            const r = await api(`/api/desired/${d.id}/buy`, { method: 'POST' });
            PO.toast(`${r.plantName} moved to My Plants with a watering schedule.`);
            load();
        })));
        actions.appendChild(PO.button('Remove', 'mini-btn', (e) => PO.busy(e.target, async () => {
            await api(`/api/desired/${d.id}`, { method: 'DELETE' });
            load();
        })));
        body.appendChild(actions);
        card.appendChild(body);
        grid.appendChild(card);
    });
}

el('addForm').addEventListener('submit', (e) => {
    e.preventDefault();
    PO.busy(el('addBtn'), async () => {
        await api('/api/desired', {
            method: 'POST',
            json: {
                scientificName: el('addScientific').value.trim(),
                commonName: el('addCommon').value.trim() || null,
                spaceId: el('addSpace').value ? Number(el('addSpace').value) : null,
                note: el('addNote').value.trim() || null,
            },
        });
        el('addForm').reset();
        load();
    });
});

document.addEventListener('po:changed', load);
load();
