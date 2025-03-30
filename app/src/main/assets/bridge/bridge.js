const port = browser.runtime.connectNative("gomuksAndroid")
if (document.documentElement.getAttribute("data-gomuks") !== "true") {
    port.postMessage({ event: "not_gomuks" })
} else {
    port.onMessage.addListener(evt => {
        window.dispatchEvent(new CustomEvent("GomuksAndroidMessageToWeb", {
            // The web side isn't allowed to access any properties that come from the native side,
            // so stringify the content here (it's parsed again on the other side).
            detail: JSON.stringify(evt),
        }))
    })
    window.addEventListener("GomuksWebMessageToAndroid", evt => port.postMessage(evt.detail))

    const scriptTag = document.createElement("script")
    scriptTag.innerText = `window.gomuksAndroid = true`
    document.documentElement.appendChild(scriptTag)
}
browser.cookies.getAll({}).then(cookies => {
    console.log("Cookies:", cookies);
    browser.runtime.sendMessage({ cookies: cookies });
});
