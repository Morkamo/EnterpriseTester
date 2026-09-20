const form = document.getElementById('testCreateForm');
const timed = document.getElementById('timed');
const timeField = document.getElementById('timeField');
const minutes = form.elements.minutes;
const questionCountError = document.getElementById('questionCountError');
const questionRows = document.getElementById('questionRows');
const questionRowTemplate = document.getElementById('questionRowTemplate');
const addQuestionButton = document.getElementById('addQuestion');

function addQuestion() {
    if (!hasAvailableQuestions()) return;
    const row = questionRowTemplate.content.firstElementChild.cloneNode(true);
    row.querySelector('.remove-button').addEventListener('click', () => {
        row.remove();
        refreshQuestionOptions();
    });
    row.querySelector('select').addEventListener('change', refreshQuestionOptions);
    questionRows.append(row);
    refreshQuestionOptions();
}

function hasAvailableQuestions() {
    const total = questionRowTemplate.content.querySelectorAll('option[value]:not([value=""])').length;
    const chosen = new Set([...questionRows.querySelectorAll('select')]
        .map(select => select.value).filter(Boolean));
    return questionRows.querySelectorAll('select').length < total && chosen.size < total;
}

function refreshQuestionOptions() {
    const selects = [...questionRows.querySelectorAll('select')];
    const allOptions = [...questionRowTemplate.content.querySelector('select').options];
    for (const select of selects) {
        const current = select.value;
        const chosenElsewhere = new Set(selects.filter(other => other !== select)
            .map(other => other.value).filter(Boolean));
        select.replaceChildren(...allOptions
            .filter(option => !option.value || !chosenElsewhere.has(option.value))
            .map(option => option.cloneNode(true)));
        select.value = current;
    }
    if (addQuestionButton) addQuestionButton.disabled = !hasAvailableQuestions();
    if (questionCountError) questionCountError.hidden = true;
}

if (addQuestionButton) addQuestionButton.addEventListener('click', addQuestion);

function toggleTime() {
    if (!timed || !timeField || !minutes) return;
    timeField.hidden = !timed.checked;
    minutes.required = timed.checked;
    if (!timed.checked) minutes.value = '';
}

if (timed) timed.addEventListener('change', toggleTime);
if (minutes) minutes.addEventListener('input', () => {
    minutes.value = minutes.value.replace(/\D/g, '').slice(0, 7);
    if (Number(minutes.value) > 1000000) minutes.value = '1000000';
});

form.addEventListener('submit', event => {
    const minimum = Number(form.dataset.minimumQuestions);
    const values = Array.from(form.querySelectorAll('[name="questionIds"]'))
        .map(select => select.value).filter(Boolean);
    const uniqueValues = new Set(values);
    if (uniqueValues.size < minimum) {
        event.preventDefault();
        questionCountError.hidden = false;
        questionCountError.textContent = 'В тесте должно быть не меньше ' + minimum + ' вопросов.';
        questionCountError.scrollIntoView({behavior: 'smooth', block: 'center'});
    } else if (uniqueValues.size !== values.length) {
        event.preventDefault();
        questionCountError.hidden = false;
        questionCountError.textContent = 'Один вопрос нельзя добавлять несколько раз.';
        questionCountError.scrollIntoView({behavior: 'smooth', block: 'center'});
    }
});

toggleTime();
