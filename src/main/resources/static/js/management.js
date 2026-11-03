const filters = document.querySelectorAll('[data-user-filter]');
const userRows = document.querySelectorAll('[data-user-row]');
const noUsersFound = document.getElementById('noUsersFound');

function filterUsers() {
    const values = {};

    filters.forEach((filter) => {
        values[filter.dataset.userFilter] = filter.value.trim().toLowerCase();
    });

    let visibleCount = 0;
    userRows.forEach((row) => {
        const matches = [...row.querySelectorAll('input, select')].every((field) => {
            const key = field.name === 'roleId' ? 'role' : field.name;

            if (!key || !values[key]) {
                return true;
            }

            const text = field.tagName === 'SELECT'
                ? field.options[field.selectedIndex].text
                : field.value;
            return text.toLowerCase().includes(values[key]);
        });

        row.hidden = !matches;
        if (matches) visibleCount++;
    });

    if (noUsersFound) noUsersFound.hidden = visibleCount !== 0;
}

filters.forEach((filter) => {
    filter.addEventListener('input', filterUsers);
    filter.addEventListener('change', filterUsers);
});

document.querySelectorAll('[data-import-kind]').forEach(importForm => {
    const kind = importForm.dataset.importKind;
    const fileInput = importForm.querySelector('input[type="file"]');
    const importButton = importForm.querySelector('button');
    const importError = document.getElementById(kind + 'ImportError');
    const dialog = document.getElementById(kind + 'ImportDialog');
    const preview = document.getElementById(kind + 'ImportPreview');
    const applyButton = document.getElementById(kind + 'ApplyImport');

    importButton.addEventListener('click', () => {
        fileInput.value = '';
        fileInput.click();
    });

    fileInput.addEventListener('change', async () => {
        if (!fileInput.files.length) return;
        importError.hidden = true;
        importButton.disabled = true;
        applyButton.disabled = false;
        try {
            const response = await fetch(importForm.action + '/preview', {
                method: 'POST',
                body: new FormData(importForm)
            });
            if (response.redirected) throw new Error('Сессия завершена. Обновите страницу и войдите снова.');
            if (!response.headers.get('Content-Type')?.includes('text/html')) {
                throw new Error('Не удалось прочитать файл. Проверьте формат CSV и размер до 10 МБ.');
            }
            preview.innerHTML = await response.text();
            if (!response.ok) {
                throw new Error(preview.querySelector('.error-message')?.textContent || 'Не удалось загрузить файл.');
            }
            dialog.showModal();
            preview.scrollTop = 0;
        } catch (error) {
            importError.textContent = error.message;
            importError.hidden = false;
            preview.replaceChildren();
        } finally {
            importButton.disabled = false;
        }
    });

    importForm.addEventListener('submit', event => {
        if (kind === 'test' && !importForm.querySelector('input[type="file"]').files.length) {
            event.preventDefault();
            return;
        }
        applyButton.disabled = true;
    });
});
