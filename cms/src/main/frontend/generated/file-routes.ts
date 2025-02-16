import { createRoute as createRoute_1 } from "@vaadin/hilla-file-router/runtime.js";
import type { AgnosticRoute as AgnosticRoute_1 } from "@vaadin/hilla-file-router/types.js";
import * as Page_1 from "../views/@index.js";
import * as Layout_1 from "../views/@layout.js";
import * as Page_2 from "../views/admin/user.js";
import * as Page_3 from "../views/customer.js";
import * as Page_4 from "../views/login.js";
import * as Page_5 from "../views/project/{projectId}/sample.js";
import * as Page_6 from "../views/project/@index.js";
import * as Page_7 from "../views/result/{resultId}/image.js";
import * as Page_8 from "../views/result/{resultId}/result.js";
import * as Page_9 from "../views/sample.js";
import * as Page_10 from "../views/system/@index.js";
import * as Page_11 from "../views/system/setting.js";
import * as Page_12 from "../views/test/@index.js";
import * as Page_13 from "../views/test/cyclic.js";
import * as Page_14 from "../views/test/destructive.js";
import * as Page_15 from "../views/test/timeCyclic.js";
const routes: readonly AgnosticRoute_1[] = [
    createRoute_1("", Layout_1, [
        createRoute_1("", Page_1),
        createRoute_1("admin", [
            createRoute_1("user", Page_2)
        ]),
        createRoute_1("customer", Page_3),
        createRoute_1("login", Page_4),
        createRoute_1("project", [
            createRoute_1("", Page_6),
            createRoute_1(":projectId", [
                createRoute_1("sample", Page_5)
            ])
        ]),
        createRoute_1("result", [
            createRoute_1(":resultId", [
                createRoute_1("image", Page_7),
                createRoute_1("result", Page_8)
            ])
        ]),
        createRoute_1("sample", Page_9),
        createRoute_1("system", [
            createRoute_1("", Page_10),
            createRoute_1("setting", Page_11)
        ]),
        createRoute_1("test", [
            createRoute_1("", Page_12),
            createRoute_1("cyclic", Page_13),
            createRoute_1("destructive", Page_14),
            createRoute_1("timeCyclic", Page_15)
        ])
    ])
];
export default routes;
