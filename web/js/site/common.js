// Menu mobile + page courante.
const toggle = document.querySelector('.nav-toggle');
const list = document.querySelector('.nav ul');
toggle?.addEventListener('click', () => {
  const open = list.classList.toggle('open');
  toggle.setAttribute('aria-expanded', String(open));
});
const here = location.pathname.split('/').pop() || 'index.html';
document.querySelectorAll('.nav ul a').forEach((a) => { if (a.getAttribute('href') === here) a.setAttribute('aria-current', 'page'); });

/** Charge le catalogue de punchlines partagé avec l'application. */
export async function loadPunchlines(base = '') {
  const r = await fetch(`${base}assets/data/punchlines.json`);
  return r.json();
}
