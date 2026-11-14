package com.sahaj.opd.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import com.sahaj.opd.application.course.CourseInputs;
import com.sahaj.opd.application.emr.EmrFormModel;
import com.sahaj.opd.application.emr.EmrInputs;
import com.sahaj.opd.application.emr.finish.FinishModels;
import com.sahaj.opd.application.emr.specialty.ChemoInputs;
import com.sahaj.opd.application.emr.specialty.VisitTemplateService;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * SAH-44, SAH-45, SAH-46 (ADR-019 D5, ADR-020 D6): the EMR request bodies are closed, as the contract says
 * ({@code additionalProperties: false}). A label, a part or any other field sent next to the real ones is refused
 * (400 validation) instead of being ignored. The one exception: a field named {@code tenantId} or {@code clinicId} is
 * accepted and ignored on the layout and the Add department bodies (the clinic is always the signed-in user's). Spring
 * Boot turns {@code FAIL_ON_UNKNOWN_PROPERTIES} off for the whole application, so this refuses an unknown field only for
 * these types; every other endpoint keeps the application default. The refusal does not carry the name that was sent.
 */
@Component
public class EmrStrictJsonModule extends SimpleModule {

    private static final long serialVersionUID = 1L;

    private static final Set<Class<?>> STRICT = Set.of(EmrInputs.LayoutInput.class,
            EmrInputs.SectionSettingsInput.class, EmrInputs.DepartmentCreateInput.class,
            EmrInputs.DepartmentSource.class, EmrInputs.OpenInput.class, EmrInputs.RecordDraftInput.class,
            EmrInputs.RecordSectionInput.class, EmrInputs.UiStateInput.class, EmrInputs.PrefillInput.class, EmrInputs.SwitchDepartmentInput.class,
            EmrInputs.AdmissionAdviceInput.class, EmrInputs.VisitTypeInput.class, EmrInputs.CopyItemsInput.class,
            EmrInputs.PrintPreferencesInput.class, EmrInputs.ScreenInput.class, EmrInputs.OncDecisionInput.class, EmrInputs.RevisionInput.class,
            ChemoInputs.OrderInput.class, ChemoInputs.FinalDose.class, ChemoInputs.CheckerInput.class,
            ChemoInputs.PinInput.class, ChemoInputs.ActionInput.class, ChemoInputs.HoldInput.class,
            ChemoInputs.ConfirmInput.class, VisitTemplateService.Input.class,
            EmrFormModel.DraftInput.class, EmrFormModel.RevisionInput.class,
            EmrFormModel.SendBackInput.class, EmrFormModel.CreateInput.class, EmrFormModel.Settings.class,
            EmrFormModel.UseIn.class, EmrFormModel.Field.class, EmrFormModel.Option.class,
            EmrFormModel.ShowIf.class, EmrFormModel.Score.class, EmrFormModel.ScoreRule.class,
            EmrFormModel.BandSetup.class, EmrFormModel.Band.class, CourseInputs.CreateInput.class,
            CourseInputs.SittingPlanInput.class, CourseInputs.AddSittingInput.class,
            CourseInputs.ChangeSittingInput.class, CourseInputs.LinkVisitInput.class, CourseInputs.RevisionInput.class,
            CourseInputs.MarkDoneInput.class, CourseInputs.DateInput.class, CourseInputs.AdmissionRequestInput.class,
            FinishModels.SignInput.class, FinishModels.FinishChoices.class, FinishModels.ErOutcome.class,
            FinishModels.IpdOrderReason.class, FinishModels.DentalChoice.class, FinishModels.PhysioChoice.class,
            FinishModels.NextSlot.class, FinishModels.Share.class, FinishModels.EditHoldInput.class,
            FinishModels.AddendumInput.class, FinishModels.AdmissionRequestInput.class);

    /** Accepted and ignored on these bodies only (SAH-53-2, SAH-54-4). */
    private static final Set<Class<?>> IGNORES_CLINIC = Set.of(EmrInputs.LayoutInput.class,
            EmrInputs.DepartmentCreateInput.class, EmrFormModel.DraftInput.class, CourseInputs.CreateInput.class);
    private static final Set<String> CLINIC_FIELDS = Set.of("tenantId", "clinicId");

    public EmrStrictJsonModule() {
        super("EmrStrictJson");
    }

    /** Marks the refusal so {@link EmrProblemHandler} can answer it as a validation problem. */
    public static final class UnknownFieldException extends MismatchedInputException {

        private static final long serialVersionUID = 1L;

        UnknownFieldException(JsonParser parser) {
            super(parser, "Unknown field in an EMR request");
        }
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addDeserializationProblemHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser parser,
                                                 JsonDeserializer<?> deserializer, Object beanOrClass,
                                                 String propertyName) throws IOException {
                Class<?> type = beanOrClass instanceof Class<?> c ? c : beanOrClass.getClass();
                if (IGNORES_CLINIC.contains(type) && CLINIC_FIELDS.contains(propertyName)) {
                    parser.skipChildren();
                    return true;
                }
                if (STRICT.contains(type)) {
                    throw new UnknownFieldException(parser);
                }
                return false;
            }
        });
    }
}
