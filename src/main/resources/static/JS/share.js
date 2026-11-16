// F12 guest page: shows the shared plants' watering and sunlight needs.

const { el, textEl } = PO;

(async function () {
    const token = new URLSearchParams(location.search).get('token');
    const content = el('content');
    try {
        if (!token) throw new Error('This page needs a share link.');
        const view = await PO.api(`/api/share/${encodeURIComponent(token)}`);
        content.appendChild(textEl('h2', `\u{1F33F} ${view.label}`));
        content.appendChild(textEl('p', `Shared by ${view.ownerName}. This link expires ${PO.formatDate(view.expiresAt)}.`, 'muted'));
        const grid = textEl('div', '', 'card-grid');
        view.plants.forEach((p) => {
            const card = textEl('article', '', 'card');
            const body = textEl('div', '', 'card-body');
            body.appendChild(textEl('h4', p.name));
            body.appendChild(textEl('p', p.scientificName, 'scientific'));
            const facts = textEl('dl', '', 'facts');
            const fact = (k, v) => {
                facts.appendChild(textEl('dt', k));
                facts.appendChild(textEl('dd', v || 'Not recorded', v ? '' : 'unknown'));
            };
            fact('Watering', [p.watering, p.wateringFrequency].filter(Boolean).join(', ') || (p.intervalDays ? `every ${p.intervalDays} days` : null));
            fact('Sunlight', p.sunlight.join(', '));
            body.appendChild(facts);
            body.appendChild(textEl('strong', 'Next waterings'));
            const ul = document.createElement('ul');
            if (!p.nextWaterings.length) ul.appendChild(textEl('li', 'None planned in the next three weeks.', 'muted'));
            p.nextWaterings.forEach((t) => ul.appendChild(textEl('li', PO.formatDateTime(t))));
            body.appendChild(ul);
            card.appendChild(body);
            grid.appendChild(card);
        });
        content.appendChild(grid);
        content.appendChild(textEl('p', 'Tip: feel the soil before watering. If the top few centimetres are still damp, wait a day.', 'hint'));
    } catch (err) {
        // UC12 alternative: invalid token -> error page
        content.replaceChildren(textEl('h2', 'Link not available'), textEl('p', err.message, 'error-box'));
    } finally {
        el('status').hidden = true;
    }
})();
