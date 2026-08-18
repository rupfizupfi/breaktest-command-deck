import type Filter_1 from "./Filter.js";
import type Matcher_1 from "./PropertyStringFilter/Matcher.js";
interface PropertyStringFilter extends Filter_1 {
    propertyId: string;
    matcher: Matcher_1;
    filterValue: string;
    "@type": "propertyString";
}
export default PropertyStringFilter;
