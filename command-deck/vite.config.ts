import {UserConfigFn} from 'vite';
import {existsSync, readFileSync} from 'fs';
import {overrideVaadinConfig} from './vite.generated';
import customFileSystemRouterPlugin from "./customFileSystemRouterPlugin";

// The Vaadin plugin writes the pinned-version block to package.json "overrides"
// under npm, but to pnpm-workspace.yaml "overrides" under pnpm. Read whichever
// exists so the dedupe list below does not silently shrink when the package
// manager changes. The generated file is a flat map, so a line scan is enough.
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

// cms frontend sources are pulled in through the 'cms' alias below, so their bare
// imports would otherwise resolve against cms/node_modules and we would ship two
// copies of react, react-router, lit and every @vaadin component. Forcing them to
// resolve from this module's root keeps one instance of each. cms' dependency set
// is a subset of this module's, so every name resolves here.
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
                // Ignore certain warnings if needed
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