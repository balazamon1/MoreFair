function initChat() {
    stompClient.send("/app/initChat/" + chatData.currentChatNumber, {}, JSON.stringify({
        'uuid': getCookie("_uuid")
    }));
}

function handleChatInit(message) {
    if (message.status === "OK") {
        if (message.content) {
            console.log(message);
            chatData = message.content;
        }
    }
    updateChat();
}

function postChat() {
    let messageInput = $('#messageInput')[0];
    const message = messageInput.value;
    if (message === "") return;
    messageInput.value = "";

    stompClient.send("/app/postChat/" + chatData.currentChatNumber, {}, JSON.stringify({
        'uuid': getCookie("_uuid"),
        'content': message
    }));
}

function handleChatUpdates(message) {
    if (message) {
        console.log(message);
        chatData.messages.unshift(message);
        if (chatData.messages.length > 30) chatData.messages.pop();
    }
    updateChat();
}