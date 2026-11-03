// Modal control

const myPlantsDialog = document.getElementById('myPlantsDialog');

document.getElementById('myPlantsCard').addEventListener('click', function(e) {
    e.preventDefault();
    myPlantsDialog.showModal();
});

document.getElementById('myPlantsClose').addEventListener('click', function() {
    myPlantsDialog.close();
});

myPlantsDialog.addEventListener('click', function(e) {
    if (e.target !== myPlantsDialog) return;
      const r = myPlantsDialog.getBoundingClientRect();
      const inside =
        e.clientX >= r.left && e.clientX <= r.right &&
        e.clientY >= r.top && e.clientY <= r.bottom;
      if (!inside) myPlantsDialog.close();
});

// Chatbox control

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