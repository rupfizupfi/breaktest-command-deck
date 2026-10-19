/**
 * The client id a view holds the server's info broadcast under (DeviceInfoService, OQ-70): one per
 * view per page load. Not sessionStorage: a duplicated tab copies it, and two tabs sharing an id
 * would release each other's hold. A reload takes a new hold; the old one lasts until its HTTP
 * session ends.
 */
const ids = new Map<string, string>();

export function infoClientId(view: string, newId: () => string = randomId): string {
    let id = ids.get(view);
    if (!id) {
        id = `${view}-${newId()}`;
        ids.set(view, id);
    }
    return id;
}

function randomId(): string {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
        return crypto.randomUUID();
    }
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}
