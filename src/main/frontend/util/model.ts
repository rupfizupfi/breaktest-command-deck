import {createDetachedModel, getObjectModelOwnAndParentGetters, Value} from "@vaadin/hilla-lit-form";
import {DetachedModelConstructor, ObjectModel} from "@vaadin/hilla-lit-form/Models.js";
import AbstractEntityModel from "Frontend/generated/ch/rupfizupfi/deck/data/AbstractEntityModel";

let optionalSymbol: symbol;

export function makeObjectEmptyValueCreatorIgnoreRelation<M extends ObjectModel>(type: DetachedModelConstructor<M>) {
    const model = createDetachedModel(type);

    if (optionalSymbol === undefined) {
        const symbols = Object.getOwnPropertySymbols(model);
        optionalSymbol = symbols.find(symbol => symbol.toString() === "Symbol(optional)")!;
    }

    return () => {
        // eslint-disable-next-line @typescript-eslint/consistent-type-assertions
        const obj: Partial<Value<M>> = {};

        // Iterate the model class hierarchy up to the ObjectModel, and extract
        // the property getter names from every prototypes
        for (const [key, getter] of getObjectModelOwnAndParentGetters(model)) {
            const propertyModel = getter.call(model);

            obj[key] = (
                // @ts-ignore
                propertyModel[optionalSymbol] ? undefined : (propertyModel instanceof AbstractEntityModel ? {} : propertyModel.constructor.createEmptyValue())
            ) as Value<M>[keyof Value<M>];
        }

        return obj as Value<M>;
    };
}
