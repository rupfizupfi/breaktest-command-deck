import { createRoute as createRoute_1 } from "@vaadin/hilla-file-router/runtime.js";
import type { AgnosticRoute as AgnosticRoute_1 } from "@vaadin/hilla-file-router/types.js";
import * as Page_1 from "../views/@index.js";
import * as Layout_1 from "../views/@layout.js";
import * as Page_2 from "../views/control.js";
import * as Page_3 from "../views/login.js";
import * as Page_4 from "../views/result/{resultId}/tracking.js";
import * as Page_5 from "../views/run.js";
const routes: readonly AgnosticRoute_1[] = [
    createRoute_1("", Layout_1, [
        createRoute_1("", Page_1),
        createRoute_1("control", Page_2),
        createRoute_1("login", Page_3),
        createRoute_1("result", [
            createRoute_1(":resultId", [
                createRoute_1("tracking", Page_4)
            ])
        ]),
        createRoute_1("run", Page_5)
    ])
];
export default routes;
