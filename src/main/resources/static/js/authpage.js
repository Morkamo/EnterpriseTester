const loginInput = document.getElementById('email');
const loginTypeMenu = document.getElementById('loginTypeMenu');
const loginTypes = loginTypeMenu.querySelectorAll('[data-login-type]');

loginTypes.forEach(button => button.addEventListener('click', () => {
    const byEmail = button.dataset.loginType === 'email';
    loginInput.name = button.dataset.loginType;
    loginInput.type = byEmail ? 'email' : 'text';
    loginInput.placeholder = button.textContent;
    loginInput.setAttribute('aria-label', button.textContent);
    loginInput.maxLength = byEmail ? 100 : 32;
    loginTypes.forEach(option => option.setAttribute('aria-pressed', option === button));
    loginTypeMenu.hidePopover();
    loginInput.focus();
}));

function showLoginResultMessage(messageText, isSuccess) {
    const authMessage = document.getElementById('auth-message');
    authMessage.textContent = messageText;
    authMessage.className = isSuccess ? 'auth-message success' : 'auth-message error';
}

async function login() {
    const password = document.getElementById('password');
    if (!loginInput.reportValidity() || !password.reportValidity()) return;

    const csrf = await fetch('/api/auth/csrf').then(response => response.json());
    const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': csrf.token
        },
        body: JSON.stringify({[loginInput.name]: loginInput.value.trim(), password: password.value})
    });
    const data = await response.json();
    showLoginResultMessage(data.message, data.success);
    if (data.success) window.location.href = '/mainpage';
}
