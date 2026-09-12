const form = document.getElementById('testForm');
const statusText = document.getElementById('saveStatus');
let pendingSave = Promise.resolve();
let navigating = false;

async function saveAnswer(data) {
    const response = await fetch(form.dataset.answerUrl, {
        method: 'POST',
        body: data
    });
    if (!response.ok) {
        throw new Error('Не удалось сохранить ответ. Нажмите «Назад» или «Вперёд», чтобы повторить сохранение.');
    }
    if (await response.json()) {
        window.location.replace(form.dataset.resultUrl);
    }
    statusText.textContent = '';
}

form.addEventListener('change', () => {
    const data = new FormData(form);
    pendingSave = pendingSave.then(() => saveAnswer(data)).catch(error => {
        statusText.textContent = error.message;
    });
});

form.addEventListener('submit', async event => {
    event.preventDefault();
    if (navigating) return;
    navigating = true;
    form.inert = true;
    const button = event.submitter;
    // Wait for autosave so an older request cannot overwrite the latest answer.
    await pendingSave;
    const action = document.createElement('input');
    action.type = 'hidden';
    action.name = button.name;
    action.value = button.value;
    form.append(action);
    form.submit();
});

window.addEventListener('pageshow', event => {
    if (event.persisted) window.location.reload();
});

if (form.dataset.deadline) {
    const remainingAtLoad = Number(form.dataset.deadline) - Number(form.dataset.serverNow);
    const loadedAt = performance.now();
    const countdown = document.getElementById('countdown');
    const timer = setInterval(() => {
        const seconds = Math.max(0, Math.ceil((remainingAtLoad - (performance.now() - loadedAt)) / 1000));
        if (countdown) {
            countdown.textContent = 'Осталось: ' + Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
        }
        if (seconds === 0) {
            clearInterval(timer);
            form.requestSubmit(document.getElementById('timeoutFinish'));
        }
    }, 250);
}
