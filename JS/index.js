document.getElementById('myPlantsCard').addEventListener('click', function(e) {
    e.preventDefault();
    document.getElementById('myPlantsDialog').showModal();
});

const launcher = document.getElementById('launcher');
const chatBox = document.getElementById('chatBox');
const messages = document.getElementById('messages');
const input = document.getElementById('msgInput');

launcher.addEventListener('click', () => {
    const open = chatBox.classList.toggle('open');
    launcher.textContent = open ? '✕' : '+';
    if (open) {
        input.focus();
    }
});

function send() {
    const text = input.value.trim();
    if (!text) return;
    const div = document.createElement('div');
    div.textContent = text;
    messages.appendChild(div);
    input.value = '';
    messages.scrollTop = messages.scrollHeight;
}

document.getElementById('sendBtn').addEventListener('click', send);
input.addEventListener('keydown', e => { 
    if (e.key === 'Enter') {
        send();
    }
});