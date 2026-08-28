package jp.co.sss.lms.service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.validation.BindingResult;

import jp.co.sss.lms.dto.AttendanceManagementDto;
import jp.co.sss.lms.dto.LoginUserDto;
import jp.co.sss.lms.entity.TStudentAttendance;
import jp.co.sss.lms.enums.AttendanceStatusEnum;
import jp.co.sss.lms.form.AttendanceForm;
import jp.co.sss.lms.form.DailyAttendanceForm;
import jp.co.sss.lms.mapper.TStudentAttendanceMapper;
import jp.co.sss.lms.util.AttendanceUtil;
import jp.co.sss.lms.util.Constants;
import jp.co.sss.lms.util.DateUtil;
import jp.co.sss.lms.util.LoginUserUtil;
import jp.co.sss.lms.util.MessageUtil;
import jp.co.sss.lms.util.TrainingTime;

/**
 * 勤怠情報（受講生入力）サービス
 *
 * @author 東京ITスクール
 */
@Service
public class StudentAttendanceService {

	@Autowired
	private DateUtil dateUtil;

	@Autowired
	private AttendanceUtil attendanceUtil;

	@Autowired
	private MessageUtil messageUtil;

	@Autowired
	private LoginUserUtil loginUserUtil;

	@Autowired
	private LoginUserDto loginUserDto;

	@Autowired
	private TStudentAttendanceMapper tStudentAttendanceMapper;

	/**
	 * 勤怠一覧情報取得
	 *
	 * @param courseId 
	 * @param lmsUserId 
	 * @return 勤怠管理画面用DTOリスト
	 */
	public List<AttendanceManagementDto> getAttendanceManagement(Integer courseId, Integer lmsUserId) {

		// 勤怠管理リストの取得
		List<AttendanceManagementDto> attendanceManagementDtoList =tStudentAttendanceMapper
				.getAttendanceManagement(courseId, lmsUserId, Constants.DB_FLG_FALSE);

		for (AttendanceManagementDto dto : attendanceManagementDtoList) {

			// 中抜け時間を設定
			if (dto.getBlankTime() != null) {
				TrainingTime blankTime = attendanceUtil.calcBlankTime(dto.getBlankTime());
				dto.setBlankTimeValue(String.valueOf(blankTime));
			}

			// 遅刻早退区分判定
			AttendanceStatusEnum statusEnum = AttendanceStatusEnum.getEnum(dto.getStatus());
			if (statusEnum != null) {
				dto.setStatusDispName(statusEnum.name);
			}
		}

		return attendanceManagementDtoList;
	}

   /**
     *ヘッダーから「勤怠」を押下
	 * @author 室月 陽翔 - Task.25
	 * @param lmsUserId LMSユーザーID
	 * @param deleteFlg 削除フラグ
	 * @param trainingDate 日付
	 * @return 未入力件数の判定結果
	 */
	
	public boolean notEnterCheck() throws ParseException {

		// SimpleDateFormatクラスでフォーマットパターンを設定し、現在日付を取得する
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		String formattedDate = sdf.format(new Date());
		Date trainingDate = sdf.parse(formattedDate);

		// 過去日の勤怠未入力件数を取得して「unfilledCount」に代入
		int unfilledCount = tStudentAttendanceMapper.notEnterCount(
				loginUserDto.getLmsUserId(),
				Constants.DB_FLG_FALSE,
				trainingDate);

		// 未入力件数が0より大きければtrue
		return unfilledCount > 0;
	}

	/**
	 * 出退勤更新前のチェック
	 *
	 * @param attendanceType 勤怠種別
	 * @return エラーメッセージ
	 */
	public String punchCheck(Short attendanceType) {

		Date trainingDate = attendanceUtil.getTrainingDate();

		// 権限チェック
		if (!loginUserUtil.isStudent()) {
			return messageUtil.getMessage(Constants.VALID_KEY_AUTHORIZATION);
		}

		// 研修日チェック
		if (!attendanceUtil.isWorkDay(loginUserDto.getCourseId(), trainingDate)) {
			return messageUtil.getMessage(Constants.VALID_KEY_ATTENDANCE_NOTWORKDAY);
		}

		// 登録情報チェック
		TStudentAttendance tStudentAttendance = tStudentAttendanceMapper
				.findByLmsUserIdAndTrainingDate(loginUserDto.getLmsUserId(),trainingDate,
				Constants.DB_FLG_FALSE);

		switch (attendanceType) {
		case Constants.CODE_VAL_ATWORK:
			if (tStudentAttendance != null 
			       && !tStudentAttendance.getTrainingStartTime().equals("")) {
				// 本日の勤怠情報は既に入力されています。直接編集してください。
				return messageUtil.getMessage(Constants.VALID_KEY_ATTENDANCE_PUNCHALREADYEXISTS);
			}
			break;

		case Constants.CODE_VAL_LEAVING:
			if (tStudentAttendance == null 
			        || tStudentAttendance.getTrainingStartTime().equals("")) {
				// 出勤情報がないため退勤情報を入力出来ません。
				return messageUtil.getMessage(Constants.VALID_KEY_ATTENDANCE_PUNCHINEMPTY);
			}

			if (!tStudentAttendance.getTrainingEndTime().equals("")) {
				// 本日の勤怠情報は既に入力されています。直接編集してください。
				return messageUtil.getMessage(Constants.VALID_KEY_ATTENDANCE_PUNCHALREADYEXISTS);
			}

			TrainingTime trainingStartTime = new TrainingTime(
					tStudentAttendance.getTrainingStartTime());
			TrainingTime trainingEndTime = new TrainingTime();

			if (trainingStartTime.compareTo(trainingEndTime) > 0) {
				// 退勤時刻は出勤時刻より後でなければいけません。
				return messageUtil.getMessage(Constants.VALID_KEY_ATTENDANCE_TRAININGTIMERANGE);
			}
			break;

		default:
			break;
		}

		return null;
	}

	/**
	 * 出勤ボタン処理
	 *
	 * @return 完了メッセージ
	 */
	public String setPunchIn() {

		// 当日日付
		Date date = new Date();
		// 本日の研修日
		Date trainingDate = attendanceUtil.getTrainingDate();
		// 現在の研修時刻
		TrainingTime trainingStartTime = new TrainingTime();
		// 遅刻早退ステータス
		AttendanceStatusEnum attendanceStatusEnum = attendanceUtil.getStatus(trainingStartTime, null);

		// 研修日の勤怠情報取得
		TStudentAttendance tStudentAttendance = tStudentAttendanceMapper
			.findByLmsUserIdAndTrainingDate(loginUserDto.getLmsUserId(),trainingDate,
				Constants.DB_FLG_FALSE);

		if (tStudentAttendance == null) {
			// 登録処理
			tStudentAttendance = new TStudentAttendance();
			tStudentAttendance.setLmsUserId(loginUserDto.getLmsUserId());
			tStudentAttendance.setTrainingDate(trainingDate);
			tStudentAttendance.setTrainingStartTime(trainingStartTime.toString());
			tStudentAttendance.setTrainingEndTime("");
			tStudentAttendance.setStatus(attendanceStatusEnum.code);
			tStudentAttendance.setNote("");
			tStudentAttendance.setAccountId(loginUserDto.getAccountId());
			tStudentAttendance.setDeleteFlg(Constants.DB_FLG_FALSE);
			tStudentAttendance.setFirstCreateUser(loginUserDto.getLmsUserId());
			tStudentAttendance.setFirstCreateDate(date);
			tStudentAttendance.setLastModifiedUser(loginUserDto.getLmsUserId());
			tStudentAttendance.setLastModifiedDate(date);
			tStudentAttendance.setBlankTime(null);

			tStudentAttendanceMapper.insert(tStudentAttendance);

		} else {
			// 更新処理
			tStudentAttendance.setTrainingStartTime(trainingStartTime.toString());
			tStudentAttendance.setStatus(attendanceStatusEnum.code);
			tStudentAttendance.setDeleteFlg(Constants.DB_FLG_FALSE);
			tStudentAttendance.setLastModifiedUser(loginUserDto.getLmsUserId());
			tStudentAttendance.setLastModifiedDate(date);
			tStudentAttendanceMapper.update(tStudentAttendance);
		}

		// 完了メッセージ
		return messageUtil.getMessage(Constants.PROP_KEY_ATTENDANCE_UPDATE_NOTICE);
	}

	/**
	 * 退勤ボタン処理
	 *
	 * @return 完了メッセージ
	 */
	public String setPunchOut() {

		// 当日日付
		Date date = new Date();
		// 本日の研修日
		Date trainingDate = attendanceUtil.getTrainingDate();

		// 研修日の勤怠情報取得
		TStudentAttendance tStudentAttendance = tStudentAttendanceMapper
				.findByLmsUserIdAndTrainingDate(loginUserDto.getLmsUserId(),
				trainingDate,
				Constants.DB_FLG_FALSE);

		// 出退勤時刻
		TrainingTime trainingStartTime = new TrainingTime(tStudentAttendance.getTrainingStartTime());
		TrainingTime trainingEndTime = new TrainingTime();
		// 遅刻早退ステータス
		AttendanceStatusEnum attendanceStatusEnum = attendanceUtil.getStatus(trainingStartTime, trainingEndTime);

		// 更新処理
		tStudentAttendance.setTrainingEndTime(trainingEndTime.toString());
		tStudentAttendance.setStatus(attendanceStatusEnum.code);
		tStudentAttendance.setDeleteFlg(Constants.DB_FLG_FALSE);
		tStudentAttendance.setLastModifiedUser(loginUserDto.getLmsUserId());
		tStudentAttendance.setLastModifiedDate(date);

		tStudentAttendanceMapper.update(tStudentAttendance);

		// 完了メッセージ
		return messageUtil.getMessage(Constants.PROP_KEY_ATTENDANCE_UPDATE_NOTICE);
	}

	/**
	 * 勤怠フォームへ設定
	 *
	 * @param attendanceManagementDtoList 勤怠管理画面用DTOリスト
	 * @return 勤怠編集フォーム
	 */
	public AttendanceForm setAttendanceForm(
			List<AttendanceManagementDto> attendanceManagementDtoList) {

		AttendanceForm attendanceForm = new AttendanceForm();
		attendanceForm.setAttendanceList(new ArrayList<DailyAttendanceForm>());
		attendanceForm.setLmsUserId(loginUserDto.getLmsUserId());
		attendanceForm.setUserName(loginUserDto.getUserName());
		attendanceForm.setLeaveFlg(loginUserDto.getLeaveFlg());
		attendanceForm.setBlankTimes(attendanceUtil.setBlankTime());

		 
	     //勤怠管理画面から勤怠情報を直接編集する、を押下
		 //室月 陽翔 - Task.26
		 //attendanceUtil.setHour
		 //時間・分をセレクトボックス
		
		attendanceForm.setHourMap(attendanceUtil.setHour());
		attendanceForm.setMinuteMap(attendanceUtil.setMinute());

		// 途中退校している場合のみ設定
		if (loginUserDto.getLeaveDate() != null) {
			attendanceForm
			         .setLeaveDate(dateUtil.dateToString(loginUserDto.getLeaveDate(), "yyyy-MM-dd"));
			attendanceForm.setDispLeaveDate(
					dateUtil.dateToString(loginUserDto.getLeaveDate(), "yyyy年M月d日"));
		}

		// 勤怠管理リストの件数分、日次の勤怠フォームに移し替え
		for (AttendanceManagementDto attendanceManagementDto : attendanceManagementDtoList) {
			DailyAttendanceForm dailyAttendanceForm = new DailyAttendanceForm();
			dailyAttendanceForm
			     .setStudentAttendanceId(attendanceManagementDto.getStudentAttendanceId());
			dailyAttendanceForm
			     .setTrainingDate(dateUtil.toString(attendanceManagementDto.getTrainingDate()));

			// 出勤時間
			// 出勤時間の分割設定
			String startTime = attendanceManagementDto.getTrainingStartTime();
			dailyAttendanceForm.setTrainingStartTime(startTime);

			if (startTime != null && startTime.contains(":")) {
				String[] startArr = startTime.split(":");
				dailyAttendanceForm.setTrainingStartTimeHour(startArr[0]);
				dailyAttendanceForm.setTrainingStartTimeMinute(startArr[1]);
			} else {
				dailyAttendanceForm.setTrainingStartTimeHour("");
				dailyAttendanceForm.setTrainingStartTimeMinute("");
			}

			//Task.26 勤怠Utilを使用して出勤時間の時・分を設定
			dailyAttendanceForm.setTrainingStartTimeHour(
					attendanceUtil.getHour(startTime));
			dailyAttendanceForm.setTrainingStartTimeMinute(
					attendanceUtil.getMinute(startTime));

			// 退勤時間
			// 退勤時間の分割設定
			String endTime = attendanceManagementDto.getTrainingEndTime();
			dailyAttendanceForm.setTrainingEndTime(endTime);

			if (endTime != null && endTime.contains(":")) {
				String[] endArr = endTime.split(":");
				dailyAttendanceForm.setTrainingEndTimeHour(endArr[0]);
				dailyAttendanceForm.setTrainingEndTimeMinute(endArr[1]);
			} else {
				dailyAttendanceForm.setTrainingEndTimeHour("");
				dailyAttendanceForm.setTrainingEndTimeMinute("");
			}

			// Task.26 勤怠Utilを使用して退勤時間の時・分を設定
			dailyAttendanceForm.setTrainingEndTimeHour(
					attendanceUtil.getHour(endTime));
			dailyAttendanceForm.setTrainingEndTimeMinute(
					attendanceUtil.getMinute(endTime));

			if (attendanceManagementDto.getBlankTime() != null) {
				dailyAttendanceForm.setBlankTime(attendanceManagementDto.getBlankTime());
				dailyAttendanceForm.setBlankTimeValue(
						String.valueOf(attendanceUtil.calcBlankTime(attendanceManagementDto.getBlankTime())));
			}

			dailyAttendanceForm.setNote(attendanceManagementDto.getNote());
			dailyAttendanceForm.setSectionName(attendanceManagementDto.getSectionName());
			dailyAttendanceForm.setIsToday(attendanceManagementDto.getIsToday());
			dailyAttendanceForm.setDispTrainingDate(
					dateUtil.dateToString(attendanceManagementDto.getTrainingDate(), "yyyy年M月d日(E)"));
			dailyAttendanceForm.setStatusDispName(attendanceManagementDto.getStatusDispName());

			attendanceForm.getAttendanceList().add(dailyAttendanceForm);
		}

		return attendanceForm;
	}

	/**
	 * 勤怠登録・更新処理
	 *
	 * @param attendanceForm 勤怠編集フォーム
	 * @return 完了メッセージ
	 * @throws ParseException 日付変換例外
	 */
	public String update(AttendanceForm attendanceForm) throws ParseException {

		Integer lmsUserId = loginUserUtil.isStudent()
				? loginUserDto.getLmsUserId()
				: attendanceForm.getLmsUserId();

		// 現在の勤怠情報（受講生入力）リストを取得
		List<TStudentAttendance> tStudentAttendanceList =
				tStudentAttendanceMapper.findByLmsUserId(lmsUserId, Constants.DB_FLG_FALSE);

		// 入力された情報を更新用のエンティティに移し替え
		Date date = new Date();

		for (DailyAttendanceForm dailyAttendanceForm : attendanceForm.getAttendanceList()) {

			// 時・分の結合処理（両方選択されている場合のみ結合、未選択なら空文字）
			String startTime = "";
			if (dailyAttendanceForm.getTrainingStartTimeHour() != null
					&& !dailyAttendanceForm.getTrainingStartTimeHour().isEmpty()
					&& dailyAttendanceForm.getTrainingStartTimeMinute() != null
					&& !dailyAttendanceForm.getTrainingStartTimeMinute().isEmpty()) {
				startTime = dailyAttendanceForm.getTrainingStartTimeHour()
						+ ":"
						+ dailyAttendanceForm.getTrainingStartTimeMinute();
			}
			dailyAttendanceForm.setTrainingStartTime(startTime);

			String endTime = "";
			if (dailyAttendanceForm.getTrainingEndTimeHour() != null
					&& !dailyAttendanceForm.getTrainingEndTimeHour().isEmpty()
					&& dailyAttendanceForm.getTrainingEndTimeMinute() != null
					&& !dailyAttendanceForm.getTrainingEndTimeMinute().isEmpty()) {
				endTime = dailyAttendanceForm.getTrainingEndTimeHour()
						+ ":"
						+ dailyAttendanceForm.getTrainingEndTimeMinute();
			}
			dailyAttendanceForm.setTrainingEndTime(endTime);

			// 更新用エンティティ作成
			TStudentAttendance tStudentAttendance = new TStudentAttendance();
			// 日次勤怠フォームから更新用のエンティティにコピー
			BeanUtils.copyProperties(dailyAttendanceForm, tStudentAttendance);

			// 研修日付
			tStudentAttendance.setTrainingDate(dateUtil.parse(dailyAttendanceForm.getTrainingDate()));

			// 現在の勤怠情報リストのうち、研修日が同じものを更新用エンティティで上書き
			for (TStudentAttendance entity : tStudentAttendanceList) {
				if (entity.getTrainingDate().equals(tStudentAttendance.getTrainingDate())) {
					tStudentAttendance = entity;
					break;
				}
			}

			tStudentAttendance.setLmsUserId(lmsUserId);
			tStudentAttendance.setAccountId(loginUserDto.getAccountId());

			// 出勤時刻整形
			TrainingTime trainingStartTime = null;
			if (!dailyAttendanceForm.getTrainingStartTime().isEmpty()) {
				trainingStartTime = new TrainingTime(dailyAttendanceForm.getTrainingStartTime());
				tStudentAttendance.setTrainingStartTime(trainingStartTime.getFormattedString());
			} else {
				tStudentAttendance.setTrainingStartTime("");
			}

			// 退勤時刻整形
			TrainingTime trainingEndTime = null;
			if (!dailyAttendanceForm.getTrainingEndTime().isEmpty()) {
				trainingEndTime = new TrainingTime(dailyAttendanceForm.getTrainingEndTime());
				tStudentAttendance.setTrainingEndTime(trainingEndTime.getFormattedString());
			} else {
				tStudentAttendance.setTrainingEndTime("");
			}

			// 中抜け時間
			tStudentAttendance.setBlankTime(dailyAttendanceForm.getBlankTime());

			// 遅刻早退ステータス
			if ((trainingStartTime != null || trainingEndTime != null)
					&& !"欠席".equals(dailyAttendanceForm.getStatusDispName())) {
				AttendanceStatusEnum attendanceStatusEnum =attendanceUtil
						.getStatus(trainingStartTime, trainingEndTime);
				tStudentAttendance.setStatus(attendanceStatusEnum.code);
			}

			// 備考
			tStudentAttendance.setNote(dailyAttendanceForm.getNote());
			// 更新者と更新日時
			tStudentAttendance.setLastModifiedUser(loginUserDto.getLmsUserId());
			tStudentAttendance.setLastModifiedDate(date);
			// 削除フラグ
			tStudentAttendance.setDeleteFlg(Constants.DB_FLG_FALSE);

			// 登録用Listへ追加
			tStudentAttendanceList.add(tStudentAttendance);
		}

		// 登録・更新処理
		for (TStudentAttendance tStudentAttendance : tStudentAttendanceList) {

			if (tStudentAttendance.getStudentAttendanceId() == null) {
				tStudentAttendance.setFirstCreateUser(loginUserDto.getLmsUserId());
				tStudentAttendance.setFirstCreateDate(date);
				tStudentAttendanceMapper.insert(tStudentAttendance);
			} else {
				tStudentAttendanceMapper.update(tStudentAttendance);
			}
		}

		// 完了メッセージ
		return messageUtil.getMessage(Constants.PROP_KEY_ATTENDANCE_UPDATE_NOTICE);
	}
	
	/**
	 * 勤怠直接変更画面、入力チェック
	 * @author h-murotsuki室月 陽翔 - Task.27
	 * @param attendanceForm 勤怠編集フォーム
	 * @param result 入力チェック結果
	 */
	public void updateInputCheck(AttendanceForm attendanceForm,BindingResult result) {

		// 日次勤怠フォームごとに入力チェック
		for (int i = 0; i < attendanceForm.getAttendanceList().size(); i++) {
			DailyAttendanceForm dailyAttendanceForm =attendanceForm.getAttendanceList().get(i);

			// 備考の文字数が100文字を超える場合
			if (dailyAttendanceForm.getNote() != null&& dailyAttendanceForm.getNote().length() > 100) {
				result.rejectValue("attendanceList[" + i + "].note",
						"maxlength",new Object[] {"備考","100"},null);
			}

			// 出勤時間の時・分を取得
			String trainingStartTimeHour =dailyAttendanceForm.getTrainingStartTimeHour();
			String trainingStartTimeMinute =dailyAttendanceForm.getTrainingStartTimeMinute();

			// 退勤時間の時・分を取得
			String trainingEndTimeHour =dailyAttendanceForm.getTrainingEndTimeHour();
			String trainingEndTimeMinute =dailyAttendanceForm.getTrainingEndTimeMinute();

			// 出勤時間の時・分が入力されているか判定
			boolean startHourInput =trainingStartTimeHour != null && !trainingStartTimeHour.isEmpty();
			boolean startMinuteInput =trainingStartTimeMinute != null && !trainingStartTimeMinute.isEmpty();

			// 退勤時間の時・分が入力されているか判定
			boolean endHourInput =trainingEndTimeHour != null && !trainingEndTimeHour.isEmpty();
			boolean endMinuteInput =trainingEndTimeMinute != null && !trainingEndTimeMinute.isEmpty();

			// 出勤時間の時・分の片方のみ入力されている場合
			if (startHourInput != startMinuteInput) {

				if (!startHourInput) {
					result.rejectValue("attendanceList[" + i + "].trainingStartTimeHour",
							"input.invalid",new Object[] {"出勤時間"},null);
				}

				if (!startMinuteInput) {
					result.rejectValue("attendanceList[" + i + "].trainingStartTimeMinute",
							"input.invalid",new Object[] {"出勤時間"},null);
				}
			}

			// 退勤時間の時・分の片方のみ入力されている場合
			if (endHourInput != endMinuteInput) {

				if (!endHourInput) {
					result.rejectValue("attendanceList[" + i + "].trainingEndTimeHour",
							"input.invalid",new Object[] {"退勤時間"},null);
				}

				if (!endMinuteInput) {
					result.rejectValue("attendanceList[" + i + "].trainingEndTimeMinute",
							"input.invalid",new Object[] {"退勤時間"},null);
				}
			}

			// 出勤時間、退勤時間が正しく入力されているか判定
			boolean startTimeInput =startHourInput && startMinuteInput;
			boolean endTimeInput =endHourInput && endMinuteInput;

			// 出勤時間に入力なし、退勤時間に入力ありの場合
			if (!startHourInput && !startMinuteInput && endTimeInput) {
				result.reject("attendance.punchInEmpty");
			}

			// 出勤時間と退勤時間が両方正しく入力されている場合
			if (startTimeInput && endTimeInput) {
				TrainingTime trainingStartTime =new TrainingTime(
						trainingStartTimeHour + ":" + trainingStartTimeMinute);
				TrainingTime trainingEndTime =new TrainingTime(
						trainingEndTimeHour + ":" + trainingEndTimeMinute);

				// 出勤時間が退勤時間より後の場合
				if (trainingStartTime.compareTo(trainingEndTime) > 0) {
					result.rejectValue("attendanceList[" + i + "].trainingEndTime",
							"attendance.trainingTimeRange");
				}

				// 中抜け時間が勤務時間を超える場合
				if (trainingStartTime.compareTo(trainingEndTime) <= 0&& dailyAttendanceForm.getBlankTime() != null) {

					int startMinute =Integer.parseInt(trainingStartTimeHour) * 60
							+Integer.parseInt(trainingStartTimeMinute);
					int endMinute =Integer.parseInt(trainingEndTimeHour) * 60
							+ Integer.parseInt(trainingEndTimeMinute);
					int trainingTime =endMinute - startMinute;

					if (dailyAttendanceForm.getBlankTime() > trainingTime) {
						result.rejectValue("attendanceList[" + i + "].blankTime",
								"attendance.blankTimeError");
					}
				}
			}
		}
	}

}