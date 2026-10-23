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

const importForm = document.getElementById('questionImportForm');
if (importForm) {
    const fileInput = document.getElementById('questionImportFile');
    const importButton = document.getElementById('importQuestions');
    const importError = document.getElementById('importError');
    const dialog = document.getElementById('questionImportDialog');
    const preview = document.getElementById('questionImportPreview');

    importButton.addEventListener('click', () => {
        fileInput.value = '';
        fileInput.click();
    });

    fileInput.addEventListener('change', async () => {
        if (!fileInput.files.length) return;
        importError.hidden = true;
        importButton.disabled = true;
        try {
            const response = await fetch('/management/questions/import/preview', {
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

    importForm.addEventListener('submit', () => {
        document.getElementById('applyImport').disabled = true;
    });
}
