/**
 * Merges cms's `file-routes.json` into the deck's. Pure, so Vitest can load it without Vite.
 *
 * Routes match by their `route` segment at the same depth. Every metadata field (title, menu,
 * rolesAllowed, ...) comes from whichever module defines it; plain objects such as `menu` merge
 * field by field. Only where both define a field with different values does the deck win, and that
 * is reported, so a collision is never silent.
 */

export interface ViewConfigRoute {
    route: string;
    children?: ViewConfigRoute[];
    [field: string]: unknown;
}

export interface RouteConflict {
    /** Full route path, e.g. `/system/setting`. */
    path: string;
    /** Dotted field name, e.g. `menu.order`. */
    field: string;
    deck: unknown;
    cms: unknown;
}

export interface MergeResult {
    routes: ViewConfigRoute[];
    conflicts: RouteConflict[];
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function deepEqual(a: unknown, b: unknown): boolean {
    return JSON.stringify(a) === JSON.stringify(b);
}

function mergeFields(deck: Record<string, unknown>, cms: Record<string, unknown>, path: string,
                     prefix: string, conflicts: RouteConflict[]): Record<string, unknown> {
    const merged: Record<string, unknown> = {...deck};
    for (const [field, cmsValue] of Object.entries(cms)) {
        if (field === 'children' && prefix === '') {
            continue;
        }
        const deckValue = deck[field];
        if (deckValue === undefined) {
            merged[field] = structuredClone(cmsValue);
        } else if (isPlainObject(deckValue) && isPlainObject(cmsValue)) {
            merged[field] = mergeFields(deckValue, cmsValue, path, `${prefix}${field}.`, conflicts);
        } else if (!deepEqual(deckValue, cmsValue)) {
            conflicts.push({path, field: `${prefix}${field}`, deck: deckValue, cms: cmsValue});
        }
    }
    return merged;
}

function mergeLevel(deckRoutes: ViewConfigRoute[], cmsRoutes: ViewConfigRoute[], basePath: string,
                    conflicts: RouteConflict[]): ViewConfigRoute[] {
    const result: ViewConfigRoute[] = deckRoutes.map(route => structuredClone(route));

    for (const cmsRoute of cmsRoutes) {
        const index = result.findIndex(route => route.route === cmsRoute.route);
        if (index === -1) {
            result.push(structuredClone(cmsRoute));
            continue;
        }

        const deckRoute = result[index];
        const path = basePath === '/' ? `/${cmsRoute.route}` : `${basePath}/${cmsRoute.route}`;
        const merged = mergeFields(deckRoute, cmsRoute, path, '', conflicts) as ViewConfigRoute;
        if (deckRoute.children || cmsRoute.children) {
            merged.children = mergeLevel(deckRoute.children ?? [], cmsRoute.children ?? [], path, conflicts);
        }
        result[index] = merged;
    }

    return result;
}

export function mergeRoutes(deckRoutes: ViewConfigRoute[], cmsRoutes: ViewConfigRoute[]): MergeResult {
    const conflicts: RouteConflict[] = [];
    const routes = mergeLevel(deckRoutes, cmsRoutes, '/', conflicts);
    return {routes, conflicts};
}
