package io.mosip.registration.processor.biodedupe.stage;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import io.mosip.kernel.core.fsadapter.spi.FileSystemAdapter;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.registration.processor.biodedupe.stage.exception.AdultCbeffNotPresentException;
import io.mosip.registration.processor.core.abstractverticle.MessageBusAddress;
import io.mosip.registration.processor.core.abstractverticle.MessageDTO;
import io.mosip.registration.processor.core.code.ApiName;
import io.mosip.registration.processor.core.code.DedupeSourceName;
import io.mosip.registration.processor.core.code.EventId;
import io.mosip.registration.processor.core.code.EventName;
import io.mosip.registration.processor.core.code.EventType;
import io.mosip.registration.processor.core.code.ModuleName;
import io.mosip.registration.processor.core.code.RegistrationExceptionTypeCode;
import io.mosip.registration.processor.core.code.RegistrationTransactionStatusCode;
import io.mosip.registration.processor.core.code.RegistrationTransactionTypeCode;
import io.mosip.registration.processor.core.constant.LoggerFileConstant;
import io.mosip.registration.processor.core.constant.PacketFiles;
import io.mosip.registration.processor.core.exception.ApisResourceAccessException;
import io.mosip.registration.processor.core.exception.util.PlatformErrorMessages;
import io.mosip.registration.processor.core.exception.util.PlatformSuccessMessages;
import io.mosip.registration.processor.core.logger.RegProcessorLogger;
import io.mosip.registration.processor.core.packet.dto.Identity;
import io.mosip.registration.processor.core.spi.packetmanager.PacketInfoManager;
import io.mosip.registration.processor.core.spi.restclient.RegistrationProcessorRestClientService;
import io.mosip.registration.processor.core.util.JsonUtil;
import io.mosip.registration.processor.core.util.RegistrationExceptionMapperUtil;
import io.mosip.registration.processor.packet.storage.dto.ApplicantInfoDto;
import io.mosip.registration.processor.packet.storage.exception.IdentityNotFoundException;
import io.mosip.registration.processor.packet.storage.utils.ABISHandlerUtil;
import io.mosip.registration.processor.packet.storage.utils.Utilities;
import io.mosip.registration.processor.rest.client.audit.builder.AuditLogRequestBuilder;
import io.mosip.registration.processor.status.code.RegistrationStatusCode;
import io.mosip.registration.processor.status.dto.InternalRegistrationStatusDto;
import io.mosip.registration.processor.status.dto.RegistrationStatusDto;
import io.mosip.registration.processor.status.dto.SyncTypeDto;
import io.mosip.registration.processor.status.service.RegistrationStatusService;

/**
 * @author Nagalakshmi
 * @author Sowmya
 *
 */
/*
 * @Transactional removed temporarily since the refid is not getting saved
 * immediately in abisref table. TODO : need to fix this.
 */
@Service
public class BioDedupeProcessor {

	@Autowired
	Utilities utilities;

	@Autowired
	private FileSystemAdapter adapter;

	@Autowired
	private PacketInfoManager<Identity, ApplicantInfoDto> packetInfoManager;

	@Autowired
	private RegistrationStatusService<String, InternalRegistrationStatusDto, RegistrationStatusDto> registrationStatusService;

	/** The core audit request builder. */
	@Autowired
	private AuditLogRequestBuilder auditLogRequestBuilder;

	@Autowired
	private RegistrationProcessorRestClientService<Object> restClientService;

	@Autowired
	private ABISHandlerUtil abisHandlerUtil;

	@Value("${config.server.file.storage.uri}")
	private String configServerFileStorageURL;

	@Value("${registration.processor.identityjson}")
	private String getRegProcessorIdentityJson;

	@Value("${mosip.kernel.applicant.type.age.limit}")
	private String ageLimit;

	private static final String NEW = "NEW";

	private static final String POST_API_PROCESS = "POST_API_PROCESS";

	private String description = "";
	public static final String FILE_SEPARATOR = "\\";

	public static final String INDIVIDUAL_BIOMETRICS = "individualBiometrics";
	private String code = "";
	/** The reg proc logger. */
	private static Logger regProcLogger = RegProcessorLogger.getLogger(BioDedupeProcessor.class);
	JSONObject demographicIdentity = null;
	RegistrationExceptionMapperUtil registrationExceptionMapperUtil = new RegistrationExceptionMapperUtil();

	List<String> machedRefIds = new ArrayList<>();

	public MessageDTO process(MessageDTO object, String stageName) {
		object.setMessageBusAddress(MessageBusAddress.BIO_DEDUPE_BUS_IN);
		object.setInternalError(Boolean.FALSE);

		boolean isTransactionSuccessful = false;

		String registrationId = object.getRid();
		InternalRegistrationStatusDto registrationStatusDto = new InternalRegistrationStatusDto();
		try {
			registrationStatusDto = registrationStatusService.getRegistrationStatus(registrationId);
			String registrationType = registrationStatusDto.getRegistrationType();
			if (registrationType.equalsIgnoreCase(SyncTypeDto.NEW.toString())) {
				String packetStatus = abisHandlerUtil.getPacketStatus(registrationStatusDto,
						RegistrationTransactionTypeCode.BIOGRAPHIC_VERIFICATION.toString());
				if (packetStatus.equalsIgnoreCase(NEW)) {
					newPacketInsertion(registrationStatusDto, object);
				} else if (packetStatus.equalsIgnoreCase(POST_API_PROCESS)) {
					newPacketHandlerIdentification(registrationStatusDto, object);
				}

			} else if (registrationType.equalsIgnoreCase(SyncTypeDto.UPDATE.toString())) {
				String packetStatus = abisHandlerUtil.getPacketStatus(registrationStatusDto,
						RegistrationTransactionTypeCode.BIOGRAPHIC_VERIFICATION.toString());
				if (packetStatus.equalsIgnoreCase(NEW)) {
					updatePacketInsertion(registrationStatusDto, object);
				} else if (packetStatus.equalsIgnoreCase(POST_API_PROCESS)) {
					updatePacketHandlerIdentification(registrationStatusDto, object);
				}

			}

			registrationStatusDto
					.setLatestTransactionTypeCode(RegistrationTransactionTypeCode.BIOGRAPHIC_VERIFICATION.toString());
			registrationStatusDto.setRegistrationStageName(stageName);
			isTransactionSuccessful = true;
			regProcLogger.debug(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					"", "BioDedupeStage::BioDedupeProcessor::exit");

		} catch (DataAccessException e) {
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PACKET_BIO_DEDUPE_REPROCESSING.name());
			registrationStatusDto.setStatusComment(PlatformErrorMessages.RPR_SYS_DATA_ACCESS_EXCEPTION.getMessage());
			registrationStatusDto.setLatestTransactionStatusCode(
					registrationExceptionMapperUtil.getStatusCode(RegistrationExceptionTypeCode.DATA_ACCESS_EXCEPTION));
			code = PlatformErrorMessages.PACKET_BIO_DEDUPE_FAILED.getCode();
			description = PlatformErrorMessages.PACKET_BIO_DEDUPE_FAILED.getMessage();
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
					code + " -- " + LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
					description + "\n" + ExceptionUtils.getStackTrace(e));
			object.setInternalError(Boolean.TRUE);
			object.setIsValid(Boolean.FALSE);
		} catch (ApisResourceAccessException e) {
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PACKET_BIO_DEDUPE_REPROCESSING.name());
			registrationStatusDto.setStatusComment(PlatformErrorMessages.RPR_SYS_API_RESOURCE_EXCEPTION.getMessage());
			registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
					.getStatusCode(RegistrationExceptionTypeCode.APIS_RESOURCE_ACCESS_EXCEPTION));
			code = PlatformErrorMessages.PACKET_BIO_DEDUPE_FAILED.getCode();
			description = PlatformErrorMessages.PACKET_BIO_DEDUPE_FAILED.getMessage();
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
					code + " -- " + LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
					description + "\n" + ExceptionUtils.getStackTrace(e));
			object.setInternalError(Boolean.TRUE);
			object.setIsValid(Boolean.FALSE);
		} catch (AdultCbeffNotPresentException ex) {
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PACKET_BIO_DEDUPE_FAILED.name());
			registrationStatusDto.setStatusComment(ExceptionUtils.getMessage(ex));
			registrationStatusDto.setLatestTransactionStatusCode(registrationExceptionMapperUtil
					.getStatusCode(RegistrationExceptionTypeCode.ADULT_CBEFF_NOT_PRESENT_EXCEPTION));
			code = PlatformErrorMessages.PACKET_BIO_DEDUPE_FAILED.getCode();
			description = PlatformErrorMessages.PACKET_BIO_DEDUPE_FAILED.getMessage();
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
					code + " -- " + LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
					description + "\n" + ExceptionUtils.getStackTrace(ex));
			object.setInternalError(Boolean.TRUE);
			object.setIsValid(Boolean.FALSE);
		} catch (IdentityNotFoundException | IOException ex) {
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PACKET_BIO_DEDUPE_FAILED.name());
			registrationStatusDto.setStatusComment(ExceptionUtils.getMessage(ex));
			registrationStatusDto.setLatestTransactionStatusCode(
					registrationExceptionMapperUtil.getStatusCode(RegistrationExceptionTypeCode.IOEXCEPTION));
			code = PlatformErrorMessages.PACKET_BIO_DEDUPE_FAILED.getCode();
			description = PlatformErrorMessages.PACKET_BIO_DEDUPE_FAILED.getMessage();
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
					code + " -- " + LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
					description + "\n" + ExceptionUtils.getStackTrace(ex));
			object.setInternalError(Boolean.TRUE);
			object.setIsValid(Boolean.FALSE);
		} catch (Exception ex) {
			registrationStatusDto.setStatusCode(RegistrationStatusCode.PACKET_BIO_DEDUPE_FAILED.name());
			registrationStatusDto.setStatusComment(ExceptionUtils.getMessage(ex));
			registrationStatusDto.setLatestTransactionStatusCode(
					registrationExceptionMapperUtil.getStatusCode(RegistrationExceptionTypeCode.EXCEPTION));
			code = PlatformErrorMessages.PACKET_BIO_DEDUPE_FAILED.getCode();
			description = PlatformErrorMessages.PACKET_BIO_DEDUPE_FAILED.getMessage();
			regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
					code + " -- " + LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
					description + "\n" + ExceptionUtils.getStackTrace(ex));
			object.setInternalError(Boolean.TRUE);
			object.setIsValid(Boolean.FALSE);
		} finally {
			registrationStatusService.updateRegistrationStatus(registrationStatusDto);

			String eventId = isTransactionSuccessful ? EventId.RPR_402.toString() : EventId.RPR_405.toString();
			String eventName = isTransactionSuccessful ? EventName.UPDATE.toString() : EventName.EXCEPTION.toString();
			String eventType = isTransactionSuccessful ? EventType.BUSINESS.toString() : EventType.SYSTEM.toString();

			String moduleId = isTransactionSuccessful ? PlatformSuccessMessages.RPR_BIO_DEDUPE_SUCCESS.getCode() : code;
			String moduleName = ModuleName.BIO_DEDUPE.name();

			auditLogRequestBuilder.createAuditRequestBuilder(description, eventId, eventName, eventType, moduleId,
					moduleName, registrationId);
		}
		return object;
	}

	private void newPacketInsertion(InternalRegistrationStatusDto registrationStatusDto, MessageDTO object)
			throws ApisResourceAccessException, IOException {
		if (checkCBEFF(registrationStatusDto.getRegistrationId())) {

			registrationStatusDto
					.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.IN_PROGRESS.toString());
			object.setIsValid(Boolean.TRUE);
			object.setMessageBusAddress(MessageBusAddress.ABIS_HANDLER_BUS_IN);
			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationStatusDto.getRegistrationId(),
					"Cbeff is present in the packet, destination stage is abis_handler");
		} else {
			registrationStatusDto.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
			object.setIsValid(Boolean.TRUE);
			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationStatusDto.getRegistrationId(),
					"Cbeff is absent in the packet for child, destination stage is UIN");
		}
	}

	private void updatePacketInsertion(InternalRegistrationStatusDto registrationStatusDto, MessageDTO object)
			throws IOException {

		InputStream idJsonStream = adapter.getFile(registrationStatusDto.getRegistrationId(),
				PacketFiles.DEMOGRAPHIC.name() + FILE_SEPARATOR + PacketFiles.ID.name());
		byte[] bytearray = IOUtils.toByteArray(idJsonStream);
		String jsonString = new String(bytearray);
		JSONObject demographicJson = JsonUtil.objectMapperReadValue(jsonString, JSONObject.class);
		demographicIdentity = JsonUtil.getJSONObject(demographicJson,
				utilities.getGetRegProcessorDemographicIdentity());

		if (demographicIdentity == null)
			throw new IdentityNotFoundException(PlatformErrorMessages.RPR_PVM_IDENTITY_NOT_FOUND.getMessage());
		JSONObject json = JsonUtil.getJSONObject(demographicIdentity, INDIVIDUAL_BIOMETRICS);
		if (!json.isEmpty()) {
			object.setIsValid(Boolean.TRUE);
			registrationStatusDto
					.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.IN_PROGRESS.toString());
			object.setMessageBusAddress(MessageBusAddress.ABIS_HANDLER_BUS_IN);

			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationStatusDto.getRegistrationId(),
					"Update packet individual biometric not null, destination stage is abis_handler");
		} else {
			registrationStatusDto.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
			object.setIsValid(Boolean.TRUE);

			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationStatusDto.getRegistrationId(),
					"Update packet individual biometric null, destination stage is UIN");
		}
	}

	private void newPacketHandlerIdentification(InternalRegistrationStatusDto registrationStatusDto, MessageDTO object)
			throws ApisResourceAccessException, IOException {

		List<String> matchedRegIds = abisHandlerUtil.getUniqueRegIds(registrationStatusDto.getRegistrationId(),
				SyncTypeDto.NEW.toString());
		if (matchedRegIds.isEmpty()) {
			registrationStatusDto.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
			object.setIsValid(Boolean.TRUE);

			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationStatusDto.getRegistrationId(), "ABIS response Details null, destination stage is UIN");

		} else {
			registrationStatusDto.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.FAILED.toString());
			object.setIsValid(Boolean.FALSE);
			packetInfoManager.saveManualAdjudicationData(matchedRegIds, registrationStatusDto.getRegistrationId(),
					DedupeSourceName.BIO);
			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationStatusDto.getRegistrationId(),
					"ABIS response Details not null, destination stage is Manual_verification");

		}
	}

	private void updatePacketHandlerIdentification(InternalRegistrationStatusDto registrationStatusDto,
			MessageDTO object) throws ApisResourceAccessException, IOException {
		List<String> matchedRegIds = abisHandlerUtil.getUniqueRegIds(registrationStatusDto.getRegistrationId(),
				SyncTypeDto.UPDATE.toString());
		if (matchedRegIds.isEmpty()) {
			registrationStatusDto.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.SUCCESS.toString());
			object.setIsValid(Boolean.TRUE);

			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationStatusDto.getRegistrationId(), "ABIS response Details null, destination stage is UIN");

		} else {
			registrationStatusDto.setLatestTransactionStatusCode(RegistrationTransactionStatusCode.FAILED.toString());
			object.setIsValid(Boolean.FALSE);
			packetInfoManager.saveManualAdjudicationData(matchedRegIds, registrationStatusDto.getRegistrationId(),
					DedupeSourceName.BIO);
			regProcLogger.info(LoggerFileConstant.SESSIONID.toString(), LoggerFileConstant.REGISTRATIONID.toString(),
					registrationStatusDto.getRegistrationId(),
					"ABIS response Details not null, destination stage is Manual_verification");

		}
	}

	private Boolean checkCBEFF(String registrationId) throws ApisResourceAccessException, IOException {

		List<String> pathSegments = new ArrayList<>();
		pathSegments.add(registrationId);
		byte[] bytefile = (byte[]) restClientService.getApi(ApiName.BIODEDUPE, pathSegments, "", "", byte[].class);

		if (bytefile != null)
			return true;

		else {

			int age = utilities.getApplicantAge(registrationId);
			int ageThreshold = Integer.parseInt(ageLimit);
			if (age < ageThreshold) {
				regProcLogger.info(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
						"Applicant type is child and Cbeff not present returning false");
				return false;
			} else {
				regProcLogger.error(LoggerFileConstant.SESSIONID.toString(),
						LoggerFileConstant.REGISTRATIONID.toString(), registrationId,
						"Applicant type is adult and Cbeff not present throwing exception");
				throw new AdultCbeffNotPresentException(
						PlatformErrorMessages.PACKET_BIO_DEDUPE_CBEFF_NOT_PRESENT.getMessage());
			}

		}

	}

}
