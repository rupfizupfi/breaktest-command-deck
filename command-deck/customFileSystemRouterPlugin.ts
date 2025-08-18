import {fileURLToPath, pathToFileURL} from "node:url";
import * as fs from 'fs';
import {type Plugin, ResolvedConfig} from "vite";

let runtimeUrls;
let isUpdateInProgress = false;

// Check if routes are already merged
function isRoutesAlreadyMerged(mainRoutes, cmsRoutes) {
    // Build a map of all main routes by path
    const mainRouteMap = new Map();

    function addRouteToMap(routes, basePath = '') {
        for (const route of routes) {
            const fullPath = `${basePath}/${route.route}`;
            mainRouteMap.set(fullPath, route);

            if (route.children && route.children.length > 0) {
                addRouteToMap(route.children, fullPath);
            }
        }
    }

    addRouteToMap(mainRoutes);

    // Check if all CMS routes exist in main routes
    function checkRoutesExist(routes, basePath = '') {
        for (const route of routes) {
            const fullPath = `${basePath}/${route.route}`;
            if (!mainRouteMap.has(fullPath)) {
                return false;
            }

            if (route.children && route.children.length > 0) {
                if (!checkRoutesExist(route.children, fullPath)) {
                    return false;
                }
            }
        }
        return true;
    }

    return checkRoutesExist(cmsRoutes);
}

// Merge route arrays
function mergeRoutesArrays(mainRoutes, cmsRoutes) {
    const result = JSON.parse(JSON.stringify(mainRoutes));

    for (const cmsRoute of cmsRoutes) {
        const matchingRoute = result.find(r => r.route === cmsRoute.route);

        if (matchingRoute) {
            // If route exists, merge children
            if (cmsRoute.children && cmsRoute.children.length > 0) {
                if (!matchingRoute.children) {
                    matchingRoute.children = [];
                }
                matchingRoute.children = mergeRoutesArrays(
                    matchingRoute.children,
                    cmsRoute.children
                );
            }
        } else {
            // Add new route
            result.push(JSON.parse(JSON.stringify(cmsRoute)));
        }
    }

    return result;
}

function mergeFileRoutes() {
    const mainRoutesPath = fileURLToPath(runtimeUrls.json);
    const cmsRoutesPath = fileURLToPath(runtimeUrls.json)
        .replace('/command-deck/', '/cms/')
        .replace('\\command-deck\\', '\\cms\\');

    console.log('Merging routes from:', mainRoutesPath, 'and', cmsRoutesPath);

    // Check if files exist
    if (!fs.existsSync(mainRoutesPath)) {
        console.warn('Main routes file not found: ' + mainRoutesPath);
        return;
    }

    if (!fs.existsSync(cmsRoutesPath)) {
        console.warn('CMS routes file not found: ' + cmsRoutesPath);
        return;
    }

    // Read route files
    const mainRoutes = JSON.parse(fs.readFileSync(mainRoutesPath, 'utf-8'));
    const cmsRoutes = JSON.parse(fs.readFileSync(cmsRoutesPath, 'utf-8'));

    // Merge routes
    const mergedRoutes = mergeRoutesArrays(mainRoutes, cmsRoutes);

    // Write back to main routes file
    fs.writeFileSync(mainRoutesPath, JSON.stringify(mergedRoutes, null, 2));
    console.log('Routes merged successfully');
}

export default function customFileSystemRouterPlugin(isDevMode = false): Plugin {
    return {
        name: 'vite-plugin-custom-file-router',
        enforce: 'pre' as const,
        configResolved: function (config: ResolvedConfig) {
            const _root = pathToFileURL(config.root);
            const _generatedDir = new URL('frontend/generated/', _root);
            const _outDir = pathToFileURL(config.build.outDir);

            runtimeUrls = {
                json: new URL('file-routes.json', isDevMode ? _generatedDir : _outDir),
            };

            config.plugins.some((plugin) => {
                if (plugin.name === 'vite-plugin-file-router') {
                    const buildStart = plugin.buildStart;
                    plugin.buildStart = async function () {
                        await buildStart.call(this).then(() => {
                            mergeFileRoutes();
                        });
                    };

                    return true;
                }
                return false;
            });
        },

        configureServer: function (server) {
            server.hot.on('fs-route-update', () => {
                if (isUpdateInProgress) {
                    console.warn('File route update already in progress, skipping');
                    return;
                }
                isUpdateInProgress = true;
                mergeFileRoutes();
                isUpdateInProgress = false;
            });
        }
    }
}