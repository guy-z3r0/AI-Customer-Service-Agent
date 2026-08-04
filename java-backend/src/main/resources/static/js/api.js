/*
 * The single place the panel talks to the backend.
 *
 * Errors always arrive as {error, detail}. This module turns them into a
 * thrown Error carrying the sentence the server wants shown, so callers can do
 * try/catch and hand the message straight to a toast.
 */

async function request(method, path, body) {
    let response;
    try {
        response = await fetch(path, {
            method,
            headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
            body: body === undefined ? undefined : JSON.stringify(body)
        });
    } catch (networkFailure) {
        throw new Error('error.network');
    }

    if (response.status === 204) return null;

    const payload = await readJson(response);
    if (!response.ok) {
        const error = new Error((payload && payload.error) || 'error.network');
        error.detail = payload && payload.detail;
        // Kept so a page can put the message under the right field: a clash is
        // about one value, an ordinary rejection is about the form.
        error.status = response.status;
        throw error;
    }
    return payload;
}

async function readJson(response) {
    try {
        return await response.json();
    } catch (notJson) {
        return null;
    }
}

export const api = {
    get: (path) => request('GET', path),
    post: (path, body) => request('POST', path, body),
    put: (path, body) => request('PUT', path, body),
    del: (path) => request('DELETE', path)
};
