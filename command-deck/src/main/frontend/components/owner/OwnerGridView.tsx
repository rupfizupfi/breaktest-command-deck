export function OwnerGridView ({item}: { item: any }) {
    if (item.owner) {
        return item.owner.username + ' (' + item.owner.name + ')';
    }
    return 'all';
}