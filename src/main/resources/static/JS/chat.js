// The chat box for talking to the AI agent (the terminal Agent.jar is the
// other front end). On normal pages it is a floating widget (bottom-right "+"
// button); on chat.html it fills the page. Messages go to POST /api/chat (with
// an optional photo); buttons under the agent's replies go to
// POST /api/chat/action. A message never changes data by itself: when it asks
// for a change, the agent replies with a proposal, and only its Yes (APPROVE)
// button makes the change.

const PoChat = (() => {
    let root;      // the .chat-box element
    let messages;  // the scrolling message list
    let input;
    let photoInput;
    let attached = null;
    let loadedHistory = false;
    let lastOffer = []; // proposals under the latest agent reply; a typed "no" cancels them

    function mount(container, fullPage) {
        root = PO.textEl('div', '', 'chat-box' + (fullPage ? ' chat-full open' : ''));
        root.id = 'chatBox';
        root.setAttribute('role', 'dialog');
        root.setAttribute('aria-label', 'Chat with the plant agent');

        const header = PO.textEl('div', '', 'chat-header');
        header.appendChild(PO.textEl('span', '\u{1F916} Plant agent'));
        const clear = PO.button('Clear', 'link-btn chat-clear', clearHistory);
        clear.title = 'Clear the conversation (the agent forgets it)';
        header.appendChild(clear);
        root.appendChild(header);

        messages = PO.textEl('div', '', 'chat-messages');
        messages.setAttribute('aria-live', 'polite');
        root.appendChild(messages);

        const preview = PO.textEl('div', '', 'chat-attachment');
        preview.hidden = true;
        root.appendChild(preview);

        const bar = PO.textEl('form', '', 'chat-input');
        photoInput = document.createElement('input');
        photoInput.type = 'file';
        photoInput.accept = 'image/jpeg,image/png';
        photoInput.hidden = true;
        photoInput.addEventListener('change', () => {
            attached = photoInput.files[0] || null;
            preview.hidden = !attached;
            preview.textContent = attached ? '\u{1F4CE} ' + attached.name + ' (click to remove)' : '';
        });
        preview.addEventListener('click', () => {
            attached = null;
            photoInput.value = '';
            preview.hidden = true;
        });
        const attach = PO.button('\u{1F4F7}', 'chat-attach', () => photoInput.click());
        attach.title = 'Attach a photo';
        attach.setAttribute('aria-label', 'Attach a photo');
        input = document.createElement('input');
        input.placeholder = 'Ask the agent... e.g. "my monstera has yellow leaves"';
        input.setAttribute('aria-label', 'Message');
        const send = PO.textEl('button', 'Send', 'chat-send');
        send.type = 'submit';
        bar.append(photoInput, attach, input, send);
        bar.addEventListener('submit', (e) => {
            e.preventDefault();
            sendMessage();
        });
        root.appendChild(bar);
        container.appendChild(root);
    }

    async function loadHistory() {
        if (loadedHistory) return;
        loadedHistory = true;
        try {
            const history = await PO.api('/api/chat/history');
            if (!history.length) {
                agentBubble({ text: 'Hi! I\'m your plant agent. You can ask me things like:\n'
                    + '- "What plant has big split leaves?" (or attach a photo)\n'
                    + '- "I just bought a snake plant, it\'s in the bedroom"\n'
                    + '- "My monstera has yellow leaves" (attach a photo if you can)\n'
                    + '- "I\'m free weekday evenings after 6, away Dec 20-27"\n'
                    + '- "Recommend a low-maintenance plant that\'s safe for cats"\n'
                    + '- "What needs water?" or "I watered the fern"\n'
                    + 'I never change anything on my own: when you ask for a change, I say exactly what I\'ll do '
                    + 'and wait for you to press **Yes**.' });
            }
            history.forEach((m) => (m.role === 'USER' ? userBubble(m.text) : agentBubble({ text: m.text })));
        } catch (err) {
            agentBubble({ text: err.message });
        }
    }

    function userBubble(text, photoName) {
        const b = PO.textEl('div', text, 'msg msg-user');
        if (photoName) b.appendChild(PO.textEl('div', '\u{1F4CE} ' + photoName, 'msg-photo'));
        messages.appendChild(b);
        scroll();
    }

    function proposalOf(action) {
        return action.payload && action.payload.proposalId;
    }

    function agentBubble(reply) {
        const actions = reply.actions || [];
        const proposes = actions.some((a) => a.type === 'APPROVE');
        const b = PO.textEl('div', '', 'msg msg-agent' + (proposes ? ' msg-proposal' : ''));
        const body = PO.textEl('div', '', 'msg-body');
        PO.renderRichText(body, reply.text);
        b.appendChild(body);
        if (reply.toolsUsed && reply.toolsUsed.length) {
            const meta = PO.textEl('div', 'Intent: ' + (reply.intent || '?') + ' · tools: '
                + reply.toolsUsed.map((t) => t.toLowerCase().replace(/_/g, ' ')).join(' → '), 'msg-meta');
            b.appendChild(meta);
        } else if (proposes) {
            b.appendChild(PO.textEl('div', 'Intent: ' + (reply.intent || '?') + ' · nothing changed yet', 'msg-meta'));
        }
        if (actions.length) {
            const row = PO.textEl('div', '', 'msg-actions');
            actions.forEach((a) => {
                const kind = a.type === 'APPROVE' ? ' approve' : a.type === 'DECLINE' ? ' decline' : '';
                const btn = PO.button(a.label, 'chip-btn' + kind, (e) => runAction(a, e.currentTarget, row));
                if (proposalOf(a)) btn.dataset.proposal = proposalOf(a);
                row.appendChild(btn);
            });
            b.appendChild(row);
        }
        lastOffer = actions.map(proposalOf).filter(Boolean);
        messages.appendChild(b);
        scroll();
        return b;
    }

    function closeProposals(ids) {
        messages.querySelectorAll('button').forEach((b) => {
            if (b.dataset.proposal && ids.includes(b.dataset.proposal)) b.disabled = true;
        });
    }

    function thinking() {
        const t = PO.textEl('div', '', 'msg msg-agent thinking');
        t.appendChild(PO.textEl('span', '', 'spinner'));
        t.appendChild(PO.textEl('span', 'Thinking...'));
        messages.appendChild(t);
        scroll();
        return t;
    }

    async function sendMessage(textOverride) {
        const text = (textOverride !== undefined ? textOverride : input.value).trim();
        const photo = textOverride !== undefined ? null : attached;
        if (!text && !photo) return;
        userBubble(text || '(photo)', photo && photo.name);
        if (textOverride === undefined) {
            input.value = '';
            attached = null;
            photoInput.value = '';
            root.querySelector('.chat-attachment').hidden = true;
        }
        const form = new FormData();
        if (text) form.append('message', text);
        if (photo) form.append('photo', photo, photo.name);
        const offered = lastOffer;
        const reply = await call(() => PO.api('/api/chat', { method: 'POST', body: form }));
        // A typed "no" cancelled what the previous reply proposed, so its buttons no longer apply.
        if (reply && reply.intent === 'DECLINE') closeProposals(offered);
    }

    async function runAction(action, btn, row) {
        if (action.type === 'OPEN') {
            location.href = action.payload.url;
            return;
        }
        if (action.type === 'SEND') {
            sendMessage(action.payload.text);
            return;
        }
        // A proposal's Yes/No buttons are answered together, wherever they appear (a typed "yes"
        // shows them again); other proposals, like "Remember: ...", stay usable.
        // A plain button closes the other plain buttons in its row.
        const id = proposalOf(action);
        (id ? messages : row).querySelectorAll('button').forEach((b) => {
            if (id ? b.dataset.proposal === id : !b.dataset.proposal) b.disabled = true;
        });
        btn.classList.add('chosen');
        const turnsRemindersOn = (action.type === 'NOTIFY' && action.payload.enabled)
            || (action.type === 'APPROVE' && action.payload.enablesReminders);
        if (turnsRemindersOn && 'Notification' in window && Notification.permission === 'default') {
            Notification.requestPermission();
        }
        await call(() => PO.api('/api/chat/action', { method: 'POST', json: { type: action.type, payload: action.payload } }));
        document.dispatchEvent(new CustomEvent('po:changed'));
    }

    async function call(work) {
        const t = thinking();
        try {
            const reply = await work();
            t.remove();
            agentBubble(reply);
            return reply;
        } catch (err) {
            t.remove();
            agentBubble({ text: err.message });
            return null;
        }
    }

    async function clearHistory() {
        if (!confirm('Clear the conversation? The agent will forget it (your plants and preferences stay).')) return;
        await PO.busy(null, () => PO.api('/api/chat/history', { method: 'DELETE' }));
        messages.replaceChildren();
        loadedHistory = false;
        loadHistory();
    }

    function scroll() {
        messages.scrollTop = messages.scrollHeight;
    }

    // Floating widget on every page except the full chat page and the guest share page.
    function initWidget() {
        if (document.getElementById('chatPage') || document.body.dataset.noChat !== undefined) return;
        const launcher = PO.textEl('button', '+', 'chat-launcher');
        launcher.type = 'button';
        launcher.setAttribute('aria-label', 'Open chat with the plant agent');
        document.body.appendChild(launcher);
        mount(document.body, false);
        launcher.addEventListener('click', () => {
            const open = root.classList.toggle('open');
            launcher.textContent = open ? '✕' : '+';
            if (open) {
                loadHistory();
                input.focus();
            }
        });
    }

    function initFullPage(container) {
        mount(container, true);
        loadHistory();
        input.focus();
    }

    document.addEventListener('DOMContentLoaded', initWidget);
    return { initFullPage, send: sendMessage };
})();
