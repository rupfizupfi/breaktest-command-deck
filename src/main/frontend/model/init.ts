import TestResultModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestResultModel";
import {makeObjectEmptyValueCreatorIgnoreRelation} from "Frontend/util/model";
import TestParameterModel from "Frontend/generated/ch/rupfizupfi/deck/data/TestParameterModel";
import SampleModel from "Frontend/generated/ch/rupfizupfi/deck/data/SampleModel";

SampleModel.createEmptyValue = makeObjectEmptyValueCreatorIgnoreRelation(SampleModel);
TestResultModel.createEmptyValue = makeObjectEmptyValueCreatorIgnoreRelation(TestResultModel);
TestParameterModel.createEmptyValue = makeObjectEmptyValueCreatorIgnoreRelation(TestParameterModel);