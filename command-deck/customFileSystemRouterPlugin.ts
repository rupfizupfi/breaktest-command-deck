import {fileURLToPath, pathToFileURL} from "node:url";
import * as fs from 'fs';
import * as path from 'path';
import {type Plugin, ResolvedConfig} from "vite";
import vitePluginFileSystemRouter from '@vaadin/hilla-file-router/vite-plugin.js';
import {mergeRoutes} from "./routeMerge";

let runtimeUrls;
let cmsRoutesPath: string;
let isUpdateInProgress = false;

/**
 * Regenerates cms's file-routes.{json,ts} from cms's own views, exactly as cms's build would. The
 * deck otherwise reads whatever cms's last build left behind, and a stale copy drops metadata such
 * as `rolesAllowed` from the deck's menu, server-side route check and runtime protect() alike.
 * isDevMode keeps the JSON in cms's generated/ in every mode, where mergeFileRoutes reads it.
 */
const cmsRouter = vitePluginFileSystemRouter({isDevMode: true}) as any;

function mergeFileRoutes(logger: ResolvedConfig['logger']) {
    const mainRoutesPath = fileURLToPath(runtimeUrls.json);

    if (!fs.existsSync(mainRoutesPath)) {
        logger.warn('Main routes file not found: ' + mainRoutesPath);
        return;
    }

    if (!fs.existsSync(cmsRoutesPath)) {
        logger.warn('CMS routes file not found: ' + cmsRoutesPath);
        return;
    }

    const mainRoutes = JSON.parse(fs.readFileSync(mainRoutesPath, 'utf-8'));
    const cmsRoutes = JSON.parse(fs.readFileSync(cmsRoutesPath, 'utf-8'));

    const {routes, conflicts} = mergeRoutes(mainRoutes, cmsRoutes);
    for (const conflict of conflicts) {
        logger.warn(`route ${conflict.path}: deck and cms disagree on ${conflict.field} `
            + `(deck ${JSON.stringify(conflict.deck)}, cms ${JSON.stringify(conflict.cms)}); the deck's value is used`);
    }

    fs.writeFileSync(mainRoutesPath, JSON.stringify(routes, null, 2));
}

export default function customFileSystemRouterPlugin(isDevMode = false): Plugin {
    let logger: ResolvedConfig['logger'];
    let cmsViewsDir: string;

    return {
        name: 'vite-plugin-custom-file-router',
        enforce: 'pre' as const,
        configResolved: function (config: ResolvedConfig) {
            logger = config.logger;
            const _root = pathToFileURL(config.root);
            const _generatedDir = new URL('frontend/generated/', _root);
            const _outDir = pathToFileURL(config.build.outDir);

            runtimeUrls = {
                json: new URL('file-routes.json', isDevMode ? _generatedDir : _outDir),
            };

            // root is <deck>/src/main/frontend.
            const cmsRoot = path.resolve(config.root, '../../../../cms/src/main/frontend');
            cmsRoutesPath = path.join(cmsRoot, 'generated', 'file-routes.json');
            cmsViewsDir = path.join(cmsRoot, 'views').replaceAll('\\', '/');
            cmsRouter.configResolved({logger: config.logger, root: cmsRoot, build: {outDir: config.build.outDir}});

            config.plugins.some((plugin) => {
                if (plugin.name === 'vite-plugin-file-router') {
                    const buildStart = plugin.buildStart as Function;
                    plugin.buildStart = async function () {
                        await buildStart.call(this);
                        await cmsRouter.buildStart.call(this);
                        mergeFileRoutes(logger);
                    };

                    return true;
                }
                return false;
            });
        },

        async hotUpdate(context) {
            if (!context.file.replaceAll('\\', '/').startsWith(cmsViewsDir)) {
                return undefined;
            }
            const modules = await cmsRouter.hotUpdate.call(this, context);
            mergeFileRoutes(logger);
            return modules;
        },

        configureServer: function (server) {
            server.hot.on('fs-route-update', () => {
                if (isUpdateInProgress) {
                    logger.warn('File route update already in progress, skipping');
                    return;
                }
                isUpdateInProgress = true;
                mergeFileRoutes(logger);
                isUpdateInProgress = false;
            });
        }
    }
}
