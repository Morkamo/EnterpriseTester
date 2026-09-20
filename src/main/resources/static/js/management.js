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
