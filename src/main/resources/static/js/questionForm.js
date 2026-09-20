const rows = document.getElementById('answerRows');
function addAnswer() {
 const row = document.createElement('div');
 row.className = 'answer-row';
 row.innerHTML = '<input name="answerText" placeholder="Вариант ответа" required>' +
  '<input name="points" type="number" min="0" value="0" required>' +
  '<button type="button" class="remove-button" onclick="this.parentElement.remove()">−</button>';
 rows.append(row);
}
if (rows.children.length === 0) { addAnswer(); addAnswer(); }

const imageCards = document.getElementById('imageCards');
const addImageButton = document.getElementById('addImage');
const imageError = document.getElementById('imageError');
const questionForm = addImageButton.form;

function updateImageLimit() {
 addImageButton.disabled = imageCards.children.length >= 10;
}

function removeImage(card) {
 if (card.dataset.imageIndex !== undefined) {
  const removed = document.createElement('input');
  removed.type = 'hidden';
  removed.name = 'removeImages';
  removed.value = card.dataset.imageIndex;
  questionForm.append(removed);
 }
 if (card.dataset.previewUrl) URL.revokeObjectURL(card.dataset.previewUrl);
 card.remove();
 imageError.hidden = true;
 updateImageLimit();
}

imageCards.addEventListener('click', event => {
 const button = event.target.closest('.remove-image');
 if (button) removeImage(button.closest('.image-card'));
});

addImageButton.addEventListener('click', () => {
 if (imageCards.children.length >= 10) return;
 const input = document.createElement('input');
 input.type = 'file';
 input.name = 'imagesToAdd';
 input.accept = '.png,.jpg,.jpeg,image/png,image/jpeg';
 input.hidden = true;
 input.addEventListener('change', () => {
  const file = input.files[0];
  if (!file) return;
  const error = imageCards.children.length >= 10
   ? 'Можно добавить не больше 10 изображений'
   : !/\.(png|jpe?g)$/i.test(file.name) ? 'Выберите PNG или JPEG'
   : file.size > 10 * 1024 * 1024 ? 'Изображение должно быть не больше 10 МБ' : '';
  imageError.textContent = error;
  imageError.hidden = !error;
  if (error) return;

  const card = document.createElement('div');
  card.className = 'image-card';
  card.dataset.previewUrl = URL.createObjectURL(file);
  const image = document.createElement('img');
  image.className = 'question-image';
  image.src = card.dataset.previewUrl;
  image.alt = file.name;
  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'remove-button remove-image';
  remove.textContent = 'Удалить';
  card.append(image, remove, input);
  imageCards.append(card);
  updateImageLimit();
 }, {once: true});
 input.click();
});
updateImageLimit();
