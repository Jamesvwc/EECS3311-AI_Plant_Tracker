// Shared header loader.
// Replaces the old PHP include: every page has <div id="site-header"></div>,
// and this script fills it with partials/header.html so the header lives in one file.

(async function loadHeader() {
    const slot = document.getElementById('site-header');
    if (!slot) return;

    try {
        const response = await fetch('partials/header.html');
        if (!response.ok) throw new Error('HTTP ' + response.status);
        slot.outerHTML = await response.text();
    } catch (err) {
        console.error('Could not load the shared header:', err);
    }
})();
