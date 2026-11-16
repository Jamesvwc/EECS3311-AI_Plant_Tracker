// Shared header loader plus the reminder bell (F04) and the chat box (CLI).
// Every page has <div id="site-header"></div>; this script fills it with
// partials/header.html so the header lives in one file.

(async function loadHeader() {
    const slot = document.getElementById('site-header');
    if (!slot) return;

    try {
        const response = await fetch('partials/header.html');
        if (!response.ok) throw new Error('HTTP ' + response.status);
        slot.outerHTML = await response.text();
    } catch (err) {
        console.error('Could not load the shared header:', err);
        return;
    }
    if (typeof PO === 'undefined') return; // pages without api.js only get the plain header

    showCurrentUser();
    setUpBell();
})();

async function showCurrentUser() {
    const chip = document.getElementById('userChip');
    if (!chip) return;
    try {
        const me = await PO.api('/api/users/me');
        chip.textContent = '\u{1F464} ' + me.displayName + ' \u25BE';
    } catch (err) {
        if (err.status === 404) {
            PO.setUserId(1); // the remembered user no longer exists
            location.reload();
        }
    }
}

// ---------- F04: reminder bell ----------

let seenNotificationIds = null;

function setUpBell() {
    const bell = document.getElementById('bellBtn');
    const panel = document.getElementById('bellPanel');
    if (!bell || !panel) return;

    bell.addEventListener('click', async () => {
        const open = panel.hidden;
        panel.hidden = !open;
        if (open) {
            await renderOverview(panel);
            PO.api('/api/notifications/read', { method: 'POST' }).then(refreshBell).catch(() => {});
        }
    });
    document.addEventListener('click', (e) => {
        if (!panel.hidden && !panel.contains(e.target) && e.target !== bell) panel.hidden = true;
    });
    refreshBell();
    setInterval(refreshBell, 60000);

    // Stage 1: the app asks which plants were watered when the user opens it.
    try {
        if (!sessionStorage.getItem('po.checkedIn') && !location.pathname.endsWith('calendar.html')) {
            sessionStorage.setItem('po.checkedIn', '1');
            PO.api('/api/calendar/overview').then((o) => {
                if (o.dueNow.length || o.overdue.length) {
                    panel.hidden = false;
                    renderOverview(panel, o);
                }
            }).catch(() => {});
        }
    } catch (e) { /* storage blocked: skip the check-in */ }
}

async function refreshBell() {
    const count = document.getElementById('bellCount');
    try {
        const list = await PO.api('/api/notifications');
        const unread = list.filter((n) => !n.read);
        count.textContent = unread.length;
        count.hidden = unread.length === 0;
        // Desktop notification for reminders that arrived since the last check (if the user allowed it).
        if (seenNotificationIds && 'Notification' in window && Notification.permission === 'granted') {
            unread.filter((n) => !seenNotificationIds.has(n.id))
                .forEach((n) => new Notification('PlantOrganizer', { body: n.message }));
        }
        seenNotificationIds = new Set(list.map((n) => n.id));
    } catch (err) {
        count.hidden = true;
    }
}

async function renderOverview(panel, overview) {
    panel.replaceChildren(PO.textEl('p', 'Loading...', 'muted'));
    let o = overview;
    try {
        o = o || await PO.api('/api/calendar/overview');
    } catch (err) {
        panel.replaceChildren(PO.textEl('p', err.message, 'error-text'));
        return;
    }
    panel.replaceChildren(PO.textEl('h4', '\u{1F4A7} Watering check-in'));
    const section = (title, items, withButton) => {
        if (!items.length) return;
        panel.appendChild(PO.textEl('h5', title));
        const ul = document.createElement('ul');
        items.forEach((i) => {
            const li = PO.textEl('li', '');
            li.appendChild(PO.textEl('span', `${i.plantName} – ${PO.formatDateTime(i.dueAt)}`));
            if (withButton) {
                li.appendChild(PO.button('Watered', 'mini-btn', async (e) => {
                    const r = await PO.busy(e.target, () => PO.api(`/api/plants/${i.plantId}/water`, { method: 'POST' }));
                    if (r) {
                        PO.toast(r.message);
                        renderOverview(panel);
                        refreshBell();
                        document.dispatchEvent(new CustomEvent('po:watered', { detail: i.plantId }));
                    }
                }));
            }
            ul.appendChild(li);
        });
        panel.appendChild(ul);
    };
    section('Missed - water these first', o.overdue, true);
    section('Due now', o.dueNow, true);
    section('Coming up this week', o.upcoming, false);
    if (!o.overdue.length && !o.dueNow.length && !o.upcoming.length) {
        panel.appendChild(PO.textEl('p', 'Nothing scheduled. Add a plant and the agent will plan its waterings.', 'muted'));
    }
    const link = PO.textEl('a', 'Open calendar →', 'panel-link');
    link.href = 'calendar.html';
    panel.appendChild(link);
}
