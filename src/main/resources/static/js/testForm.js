const form = document.getElementById('testCreateForm');
const timed = document.getElementById('timed');
const timeField = document.getElementById('timeField');
const minutes = form.elements.minutes;
const questionCountError = document.getElementById('questionCountError');
const questionRows = document.getElementById('questionRows');
const questionRowTemplate = document.getElementById('questionRowTemplate');
const questionPicker = document.getElementById('questionPicker');

questionPicker.addEventListener('change', event => {
    const checkbox = event.target;
    const existing = questionRows.querySelector('[data-question-id="' + checkbox.value + '"]');
    if (!checkbox.checked) {
        existing?.remove();
    } else if (!existing) {
        const row = questionRowTemplate.content.firstElementChild.cloneNode(true);
        row.dataset.questionId = checkbox.value;
        row.querySelector('span').textContent = checkbox.previousElementSibling.textContent;
        row.querySelector('.remove-button').addEventListener('click', () => {
            checkbox.checked = false;
            checkbox.dispatchEvent(new Event('change', {bubbles: true}));
        });
        questionRows.append(row);
    }
    questionCountError.hidden = true;
});

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
    const values = Array.from(form.querySelectorAll('[name="questionIds"]:checked'))
        .map(checkbox => checkbox.value);
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
