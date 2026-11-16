// F11 - Plant Sharing with Friends, and F12 - Watering Schedule Sharer (links).

const { el, textEl, api } = PO;

async function loadFriends() {
    const [users, friends] = await Promise.all([api('/api/users'), api('/api/friends')]);
    const me = Number(PO.userId());
    const friendIds = new Set(friends.map((f) => f.id));
    const select = el('friendSelect');
    select.replaceChildren(new Option('Choose a user to add...', ''));
    users.filter((u) => u.id !== me && !friendIds.has(u.id))
        .forEach((u) => select.appendChild(new Option(`${u.displayName} (#${u.id})`, u.id)));

    const list = el('friendList');
    list.replaceChildren();
    if (!friends.length) list.appendChild(textEl('p', 'No friends yet.', 'muted'));
    friends.forEach((f) => {
        const row = textEl('div', '', 'panel-row friend-row');
        row.appendChild(textEl('strong', f.name));
        const allow = textEl('label', '', 'toggle');
        const box = document.createElement('input');
        box.type = 'checkbox';
        box.checked = f.canSeeMyPlants;
        box.addEventListener('change', async () => {
            try {
                await api(`/api/friends/${f.id}/can-view`, { method: 'PUT', json: { allowed: box.checked } });
                PO.toast(box.checked ? `${f.name} can now see your plant names.` : `${f.name} can no longer see your plants.`);
            } catch (err) {
                box.checked = !box.checked;
                PO.toast(err.message, 'error');
            }
        });
        allow.append(box, document.createTextNode(' can see my plants'));
        row.appendChild(allow);
        row.appendChild(PO.button(`View ${f.name}'s plants`, 'mini-btn', () => viewPlants(f)));
        row.appendChild(PO.button('Remove', 'link-btn', async () => {
            if (!confirm(`Remove ${f.name} as a friend?`)) return;
            await api(`/api/friends/${f.id}`, { method: 'DELETE' });
            loadFriends();
        }));
        list.appendChild(row);
    });
}

async function viewPlants(friend) {
    const box = el('friendPlants');
    box.replaceChildren(textEl('h4', `${friend.name}'s plants`));
    try {
        const plants = await api(`/api/friends/${friend.id}/plants`);
        const ul = document.createElement('ul');
        if (!plants.length) ul.appendChild(textEl('li', 'They have no plants yet.', 'muted'));
        plants.forEach((p) => {
            const li = textEl('li', p.name + ' ');
            li.appendChild(textEl('span', p.scientificName, 'scientific'));
            ul.appendChild(li);
        });
        box.appendChild(ul);
    } catch (err) {
        // UC11 alternative: access denied
        box.appendChild(textEl('p', '\u{1F512} ' + err.message, 'notice'));
    }
}

el('addFriendForm').addEventListener('submit', (e) => {
    e.preventDefault();
    if (!el('friendSelect').value) return;
    PO.busy(el('addFriendBtn'), async () => {
        const f = await api('/api/friends', { method: 'POST', json: { friendId: Number(el('friendSelect').value) } });
        PO.toast(`${f.name} added. Tick "can see my plants" to share your list with them.`);
        loadFriends();
    });
});

// ---------- F12 share links ----------

async function loadShare() {
    const [plants, links] = await Promise.all([api('/api/plants'), api('/api/share')]);
    const box = el('sharePlants');
    box.replaceChildren();
    if (!plants.length) box.appendChild(textEl('p', 'Add plants first.', 'muted'));
    plants.forEach((p) => {
        const label = textEl('label', '', 'toggle');
        const c = document.createElement('input');
        c.type = 'checkbox';
        c.value = p.id;
        c.checked = true;
        label.append(c, document.createTextNode(' ' + p.name));
        label.style.marginRight = '14px';
        box.appendChild(label);
    });

    const list = el('linkList');
    list.replaceChildren();
    if (!links.length) list.appendChild(textEl('p', 'No links yet.', 'muted'));
    links.forEach((l) => {
        const url = new URL(l.path, location.href).href;
        const row = textEl('div', '', 'panel-row');
        row.style.marginBottom = '8px';
        row.appendChild(textEl('strong', l.label));
        row.appendChild(textEl('span', `${l.plantNames.join(', ')} · expires ${PO.formatDate(l.expiresAt)}`, 'muted'));
        const a = textEl('a', 'Open');
        a.href = url;
        a.target = '_blank';
        a.rel = 'noopener';
        row.appendChild(a);
        row.appendChild(PO.button('Copy link', 'mini-btn', async () => {
            try {
                await navigator.clipboard.writeText(url);
                PO.toast('Link copied.');
            } catch (err) {
                prompt('Copy this link:', url);
            }
        }));
        row.appendChild(PO.button('Revoke', 'link-btn', async () => {
            await api(`/api/share/${encodeURIComponent(l.token)}`, { method: 'DELETE' });
            loadShare();
        }));
        list.appendChild(row);
    });
}

el('shareForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const plantIds = [...document.querySelectorAll('#sharePlants input:checked')].map((c) => Number(c.value));
    PO.busy(el('shareBtn'), async () => {
        await api('/api/share', { method: 'POST', json: { plantIds, label: el('shareLabel').value.trim() || null } });
        el('shareLabel').value = '';
        PO.toast('Link created. Copy it and send it to your plant sitter.');
        loadShare();
    });
});

loadFriends().catch((err) => el('friendList').replaceChildren(textEl('p', err.message, 'error-text')));
loadShare().catch((err) => el('linkList').replaceChildren(textEl('p', err.message, 'error-text')));
