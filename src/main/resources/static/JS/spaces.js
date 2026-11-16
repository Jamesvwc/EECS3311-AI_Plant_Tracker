// F10 - Room Builder. The tree mirrors the Composite on the server: rooms
// contain rooms and spots, spots hold plants, and selecting any space lists
// all plants below it. The floor plan is the "zoom in" stretch goal: drag
// spaces to where they really are; plant markers show watering state and a
// stethoscope when their latest health check needs attention.

const { el, textEl, api } = PO;

const state = { tree: [], flat: [], lights: [], plants: [], selected: null };

async function load(selectId) {
    const [tree, lights, plants] = await Promise.all([api('/api/spaces'), api('/api/spaces/light-levels'), api('/api/plants')]);
    state.tree = tree;
    state.lights = lights;
    state.plants = plants;
    state.flat = [];
    const walk = (list) => list.forEach((s) => { state.flat.push(s); walk(s.children); });
    walk(tree);
    renderTree();
    fillCreateForm();
    const id = selectId || (state.selected && state.selected.id) || (tree[0] && tree[0].id);
    if (id && state.flat.some((s) => s.id === id)) select(id); else renderDetail(null);
}

function renderTree() {
    const root = el('tree');
    root.replaceChildren();
    el('treeEmpty').hidden = state.tree.length > 0;
    const build = (spaces, parent) => spaces.forEach((s) => {
        const li = document.createElement('li');
        const b = PO.button((s.kind === 'ROOM' ? '\u{1F6AA} ' : '\u{1FAB4} ') + s.name, '', () => select(s.id));
        b.dataset.id = s.id;
        b.appendChild(textEl('span', ` (${s.plantCount})`, 'count'));
        li.appendChild(b);
        if (s.children.length) {
            const ul = document.createElement('ul');
            build(s.children, ul);
            li.appendChild(ul);
        }
        parent.appendChild(li);
    });
    build(state.tree, root);
}

function fillCreateForm() {
    const parent = el('newParent');
    const keep = parent.value;
    parent.replaceChildren(new Option('Top level (no parent)', ''));
    state.flat.filter((s) => s.kind === 'ROOM').forEach((s) => parent.appendChild(new Option('Inside: ' + s.path, s.id)));
    parent.value = keep;
    const light = el('newLight');
    if (!light.options.length) {
        light.appendChild(new Option('Light: same as parent', ''));
        state.lights.forEach((l) => light.appendChild(new Option('Light: ' + l.label, l.value)));
    }
}

el('createForm').addEventListener('submit', (e) => {
    e.preventDefault();
    PO.busy(el('createBtn'), async () => {
        const created = await api('/api/spaces', {
            method: 'POST',
            json: {
                name: el('newName').value.trim(),
                spot: el('newKind').value === 'spot',
                parentId: el('newParent').value ? Number(el('newParent').value) : null,
                light: el('newLight').value || null,
            },
        });
        el('newName').value = '';
        await load(created.id);
    });
});

async function select(id) {
    document.querySelectorAll('.tree button').forEach((b) => b.classList.toggle('selected', Number(b.dataset.id) === id));
    const space = await api(`/api/spaces/${id}`);
    state.selected = space;
    renderDetail(space);
}

async function renderDetail(space) {
    const panel = el('detail');
    if (!space) {
        panel.replaceChildren(textEl('p', 'Select a space on the left, or add your first room.', 'muted'));
        return;
    }
    panel.replaceChildren();

    // Breadcrumbs ("zoom out")
    const crumbs = textEl('div', '', 'breadcrumbs');
    const parts = space.path.split(' > ');
    let path = '';
    parts.forEach((p, i) => {
        path = path ? path + ' > ' + p : p;
        const target = state.flat.find((s) => s.path === path);
        if (i < parts.length - 1 && target) {
            crumbs.appendChild(PO.button(p, '', () => select(target.id)));
            crumbs.appendChild(document.createTextNode(' › '));
        } else {
            crumbs.appendChild(textEl('strong', p));
        }
    });
    panel.appendChild(crumbs);

    // Edit name / light
    const form = textEl('form', '', 'panel-row');
    const name = document.createElement('input');
    name.value = space.name;
    name.setAttribute('aria-label', 'Space name');
    const light = document.createElement('select');
    light.appendChild(new Option('Same light as parent', ''));
    state.lights.forEach((l) => light.appendChild(new Option(l.label, l.value)));
    light.value = space.lightLevel || '';
    const save = textEl('button', 'Save', 'secondary-btn');
    save.type = 'submit';
    form.append(name, light, save);
    form.addEventListener('submit', (e) => {
        e.preventDefault();
        PO.busy(save, async () => {
            await api(`/api/spaces/${space.id}`, { method: 'PUT', json: { name: name.value, light: light.value || null, inheritLight: !light.value } });
            await load(space.id);
        });
    });
    panel.appendChild(form);
    panel.appendChild(textEl('p', `${space.kind === 'ROOM' ? 'Room' : 'Spot'} · light: ${space.effectiveLight || 'not set'} · ${space.plantCount} plant(s) inside`, 'hint'));

    // Floor plan for rooms (zoom-in view)
    if (space.kind === 'ROOM') {
        panel.appendChild(textEl('h4', 'Floor plan'));
        panel.appendChild(textEl('p', space.children.length ? 'Drag spaces to where they are. Double-click a room to zoom in.' : 'Add rooms or spots inside this room to lay them out here.', 'hint'));
        panel.appendChild(floorPlan(space));
    }

    // All plants inside (Composite getPlants)
    panel.appendChild(textEl('h4', `Plants in ${space.name}`));
    const plants = await api(`/api/spaces/${space.id}/plants`);
    const ul = textEl('ul', '', 'plant-rows');
    if (!plants.length) ul.appendChild(textEl('li', 'No plants here yet.', 'muted'));
    plants.forEach((p) => {
        const li = document.createElement('li');
        const label = textEl('span', '');
        const link = textEl('a', p.name);
        link.href = `currentlistofplants.html?plant=${p.id}`;
        label.append(link, document.createTextNode(' · ' + (p.location || '')));
        li.append(label, PO.statusBadge(p.wateringStatus));
        ul.appendChild(li);
    });
    panel.appendChild(ul);

    // Assign a plant here
    const assign = textEl('div', '', 'panel-row');
    const pick = document.createElement('select');
    pick.appendChild(new Option('Choose a plant to put here...', ''));
    state.plants.forEach((p) => pick.appendChild(new Option(p.name + (p.location ? ` (now: ${p.location})` : ''), p.id)));
    assign.append(pick, PO.button('Put it here', 'secondary-btn', (e) => {
        if (!pick.value) return;
        PO.busy(e.target, async () => {
            await api(`/api/spaces/${space.id}/plants/${pick.value}`, { method: 'POST' });
            PO.toast(space.kind === 'ROOM' ? `Placed in ${space.name} (General area).` : `Placed on ${space.name}.`);
            await load(space.id);
        });
    }));
    panel.appendChild(assign);

    const del = PO.button('Delete this space', 'danger-btn', async () => {
        if (!confirm(`Delete ${space.name} and everything inside it? Plants inside become unplaced (not deleted).`)) return;
        await PO.busy(del, () => api(`/api/spaces/${space.id}`, { method: 'DELETE' }));
        state.selected = null;
        await load();
    });
    del.style.marginTop = '18px';
    panel.appendChild(del);
}

function floorPlan(room) {
    const floor = textEl('div', '', 'floor');
    room.children.forEach((child) => {
        const node = textEl('div', '', 'node ' + child.kind.toLowerCase());
        node.style.left = child.posX + '%';
        node.style.top = child.posY + '%';
        const title = textEl('div', '', 'node-title');
        title.appendChild(textEl('span', (child.kind === 'ROOM' ? '\u{1F6AA} ' : '\u{1FAB4} ') + child.name));
        if (child.kind === 'ROOM') {
            const zoom = PO.button('\u{1F50D}', 'zoom', () => select(child.id));
            zoom.title = 'Zoom in';
            title.appendChild(zoom);
        }
        node.appendChild(title);
        const dots = textEl('div', '', 'dot-row');
        const markers = child.kind === 'SPOT' ? child.plants : collect(child);
        markers.forEach((p) => {
            const d = textEl('span', p.name, 'dot ' + p.wateringStatus.toLowerCase() + (p.needsAttention ? ' attention' : ''));
            d.title = `${p.name}: ${p.wateringStatus.toLowerCase()}${p.needsAttention ? ', health needs attention' : ''}${p.noteCount ? `, ${p.noteCount} note(s)` : ''}`;
            dots.appendChild(d);
        });
        if (!markers.length) dots.appendChild(textEl('span', 'empty', 'muted'));
        node.appendChild(dots);
        node.addEventListener('dblclick', () => select(child.id));
        makeDraggable(node, floor, child);
        floor.appendChild(node);
    });
    return floor;
}

function collect(space) {
    return space.kind === 'SPOT' ? space.plants : space.children.flatMap(collect);
}

function makeDraggable(node, floor, space) {
    let dragging = false;
    node.addEventListener('pointerdown', (e) => {
        if (e.target.closest('button')) return;
        dragging = true;
        node.setPointerCapture(e.pointerId);
    });
    node.addEventListener('pointermove', (e) => {
        if (!dragging) return;
        const r = floor.getBoundingClientRect();
        const x = Math.max(5, Math.min(95, ((e.clientX - r.left) / r.width) * 100));
        const y = Math.max(8, Math.min(92, ((e.clientY - r.top) / r.height) * 100));
        node.style.left = x + '%';
        node.style.top = y + '%';
        space.posX = x;
        space.posY = y;
    });
    node.addEventListener('pointerup', async () => {
        if (!dragging) return;
        dragging = false;
        try {
            await api(`/api/spaces/${space.id}/position`, { method: 'PUT', json: { x: space.posX, y: space.posY } });
        } catch (err) {
            PO.toast(err.message, 'error');
        }
    });
}

document.addEventListener('po:changed', () => load());
load().catch((err) => el('detail').replaceChildren(textEl('p', err.message, 'error-text')));
