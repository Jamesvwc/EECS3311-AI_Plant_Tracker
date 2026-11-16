// Shared helpers for every page: the current user, JSON requests with clear
// errors, and safe DOM/text rendering. Load this before any page script.
//
// There is no login (out of scope), so the browser remembers which user it is
// acting as in localStorage and sends it as the X-User-Id header. Switch users
// on the Profile page (used to demonstrate friends and sharing).

const PO = (() => {
    const USER_KEY = 'po.userId';

    function userId() {
        try {
            return localStorage.getItem(USER_KEY) || '1';
        } catch (e) {
            return '1';
        }
    }

    function setUserId(id) {
        try {
            localStorage.setItem(USER_KEY, String(id));
        } catch (e) { /* private mode: stays on user 1 */ }
    }

    // Fetches JSON. Throws an Error whose .retryable says whether "Try again" makes sense.
    async function api(url, options = {}) {
        const opts = { ...options, headers: { 'X-User-Id': userId(), ...(options.headers || {}) } };
        if (opts.json !== undefined) {
            opts.body = JSON.stringify(opts.json);
            opts.headers['Content-Type'] = 'application/json';
            delete opts.json;
        }
        let response;
        try {
            response = await fetch(url, opts);
        } catch (networkError) {
            throw requestError('Could not reach the PlantOrganizer server. Is it running?', true);
        }
        let data = null;
        const text = await response.text();
        if (text) {
            try {
                data = JSON.parse(text);
            } catch (e) {
                data = null;
            }
        }
        if (!response.ok) {
            const message = (data && data.error) || `The server returned HTTP ${response.status}.`;
            const retryable = data && typeof data.retryable === 'boolean' ? data.retryable : response.status >= 500;
            throw requestError(message, retryable, response.status);
        }
        return data;
    }

    function requestError(message, retryable, status) {
        const error = new Error(message);
        error.retryable = retryable;
        error.status = status;
        return error;
    }

    const el = (id) => document.getElementById(id);

    function textEl(tag, text, className) {
        const node = document.createElement(tag);
        if (text !== undefined && text !== null) node.textContent = text;
        if (className) node.className = className;
        return node;
    }

    function button(label, className, onClick) {
        const b = textEl('button', label, className || 'secondary-btn');
        b.type = 'button';
        if (onClick) b.addEventListener('click', onClick);
        return b;
    }

    // Renders agent text safely: paragraphs, "- " bullets, **bold** and _italic_ lines.
    function renderRichText(container, text) {
        container.replaceChildren();
        let list = null;
        (text || '').split('\n').forEach((rawLine) => {
            const line = rawLine.trim();
            if (!line) {
                list = null;
                return;
            }
            const bullet = line.match(/^[-*•]\s+(.*)$/) || line.match(/^\d+[.)]\s+(.*)$/);
            if (bullet) {
                if (!list) {
                    list = document.createElement('ul');
                    container.appendChild(list);
                }
                list.appendChild(withBold(document.createElement('li'), bullet[1]));
            } else {
                list = null;
                const italic = line.match(/^_(.*)_$/);
                const p = withBold(document.createElement('p'), (italic ? italic[1] : line).replace(/^#+\s*/, ''));
                if (italic) p.className = 'muted';
                container.appendChild(p);
            }
        });
    }

    function withBold(parent, text) {
        text.split(/(\*\*[^*]+\*\*)/).forEach((part) => {
            if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
                parent.appendChild(textEl('strong', part.slice(2, -2)));
            } else if (part) {
                parent.appendChild(document.createTextNode(part));
            }
        });
        return parent;
    }

    function formatDateTime(iso) {
        if (!iso) return '';
        const d = new Date(iso);
        return d.toLocaleString(undefined, { weekday: 'short', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
    }

    function formatDate(iso) {
        if (!iso) return '';
        return new Date(iso).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
    }

    function toast(message, kind) {
        let box = document.getElementById('toast');
        if (!box) {
            box = textEl('div', '', 'toast');
            box.id = 'toast';
            box.setAttribute('role', 'status');
            document.body.appendChild(box);
        }
        box.textContent = message;
        box.className = 'toast show' + (kind === 'error' ? ' toast-error' : '');
        clearTimeout(box._timer);
        box._timer = setTimeout(() => box.classList.remove('show'), 4000);
    }

    function statusBadge(status) {
        const labels = { OK: 'Watered', DUE: 'Needs water', OVERDUE: 'Missed watering', UPCOMING: 'Upcoming' };
        return textEl('span', labels[status] || status, 'badge status-' + String(status).toLowerCase());
    }

    function placeholderImage() {
        return textEl('div', '\u{1F33F}', 'img-placeholder');
    }

    function image(url, alt) {
        if (!url) return placeholderImage();
        const img = document.createElement('img');
        img.src = url;
        img.alt = alt || '';
        img.loading = 'lazy';
        img.addEventListener('error', () => img.replaceWith(placeholderImage()), { once: true });
        return img;
    }

    // Wraps an async action on a button: disables it and shows errors as a toast.
    async function busy(btn, work) {
        const label = btn ? btn.textContent : null;
        if (btn) {
            btn.disabled = true;
            btn.textContent = 'Working...';
        }
        try {
            return await work();
        } catch (err) {
            toast(err.message, 'error');
            return undefined;
        } finally {
            if (btn) {
                btn.disabled = false;
                btn.textContent = label;
            }
        }
    }

    return { userId, setUserId, api, el, textEl, button, renderRichText, formatDateTime, formatDate, toast,
             statusBadge, image, placeholderImage, busy };
})();
