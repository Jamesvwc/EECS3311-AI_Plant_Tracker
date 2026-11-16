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

// The chat box (CLI) is added to every page by JS/chat.js.
