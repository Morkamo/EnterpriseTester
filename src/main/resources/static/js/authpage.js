function showLoginResultMessage(messageText, isSuccess) {

    const authMessage = document.getElementById('auth-message');
    authMessage.textContent = messageText;

    if (isSuccess) {
        authMessage.className = "auth-message success";
    }
    else {
        authMessage.className = "auth-message error";
    }
}


async function login() {

    const email = document.getElementById('email').value;
    const password = document.getElementById('password').value;

    const response = await fetch("/api/auth/login", {
        method: "POST",

        headers: {
            "Content-Type": "application/json"
        },

        body: JSON.stringify({
            email,
            password
        })
    });


    const data = await response.json();

    showLoginResultMessage(data.message, data.success);

    if (data.success) {
        window.location.href = "/mainpage";
    }
}
