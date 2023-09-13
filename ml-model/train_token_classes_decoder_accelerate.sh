export PYTHONPATH="./"
accelerate launch --config_file accelerate_config_fsdp.yaml scripts/train/run_classifier_accelerate.py \
--do_train \
--do_eval \
--do_predict \
--model_type codet5+ \
--tokenizer_name Salesforce/codet5p-770m \
--model_name_or_path Salesforce/codet5p-770m \
--tratto_model_type token_classes \
--task_name tokenClasses_classifier_decoder \
--max_seq_length 512 \
--batch_size 16 \
--learning_rate 1e-5 \
--num_epochs 5 \
--save_steps 4000 \
--accumulation_steps 1 \
--test_ratio 0.1 \
--validation_ratio 0.1 \
--data_dir ./dataset/token-classes-dataset-cleaned \
--output_dir ./output_token_classes_decoder_label_770 \
--classification_type label_prediction