import {UserConfigFn} from 'vite';
import {existsSync, readFileSync} from 'fs';
import {overrideVaadinConfig} from './vite.generated';
import customFileSystemRouterPlugin from "./customFileSystemRouterPlugin";

// Vaadin writes its pinned versions to package.json "overrides" under npm and to
// pnpm-workspace.yaml "overrides" under pnpm; read both so the dedupe list stays complete.
// The generated file is a flat map, so a line scan is enough.
function pnpmWorkspaceOverrides(): string[] {
    const file = __dirname + '/pnpm-workspace.yaml';
    if (!existsSync(file)) {
        return [];
    }
    const names: string[] = [];
    let inOverrides = false;
    for (const line of readFileSync(file, 'utf-8').split('\n')) {
        if (/^overrides:/.test(line)) {
            inOverrides = true;
        } else if (inOverrides && /^\S/.test(line)) {
            break; // next top-level key
        } else if (inOverrides) {
            const match = line.match(/^\s+'?([^':]+?)'?\s*:/);
            if (match) {
                names.push(match[1]);
            }
        }
    }
    return names;
}

// cms sources come in through the 'cms' alias, so their bare imports would resolve against
// cms/node_modules and ship second copies of react, lit and @vaadin. Dedupe resolves them from
// this module instead; cms' dependencies are a subset of this module's.
const packageJson = JSON.parse(readFileSync(__dirname + '/package.json', 'utf-8'));
const dedupe = [...new Set([
    ...Object.keys(packageJson.dependencies ?? {}),
    ...Object.keys(packageJson.overrides ?? {}),
    ...pnpmWorkspaceOverrides(),
])];

const customConfig: UserConfigFn = (env) => ({
    resolve: {
        alias: {
            'cms': __dirname + '/../cms/src/main/frontend',
        },
        dedupe,
    },
    build: {
        rollupOptions: {
            // external: id => id.includes('/cms/node_modules/'),
            preserveEntrySignatures: 'strict',
            onwarn(warning, warn) {
                if (warning.code === 'MIXED_EXPORTS') {
                    return;
                }
                warn(warning);
            }
        }
    },

    plugins: [
        customFileSystemRouterPlugin(env.mode === 'development')
    ],
});

export default overrideVaadinConfig(customConfig);