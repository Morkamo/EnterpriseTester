const loginInput = document.getElementById('login');

function showLoginResultMessage(messageText, isSuccess) {
    const authMessage = document.getElementById('auth-message');
    authMessage.textContent = messageText;
    authMessage.className = isSuccess ? 'auth-message success' : 'auth-message error';
}

async function login() {
    const password = document.getElementById('password');
    if (!loginInput.reportValidity() || !password.reportValidity()) return;

    const identifier = loginInput.value.trim();
    const loginType = identifier.includes('@') ? 'email' : 'username';
    const csrf = await fetch('/api/auth/csrf').then(response => response.json());
    const response = await fetch('/api/auth/login', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': csrf.token
        },
        body: JSON.stringify({[loginType]: identifier, password: password.value})
    });
    const data = await response.json();
    showLoginResultMessage(data.message, data.success);
    if (data.success) window.location.href = '/mainpage';
}
